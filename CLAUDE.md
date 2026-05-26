# Claude Code for JetBrains — Plugin nativo

Plugin nativo de **IntelliJ Platform (Kotlin)** que integra **Claude Code** en los IDEs de JetBrains (objetivo inicial: PyCharm e IntelliJ IDEA). Meta del proyecto: una integración **mucho más funcional** que el JetBrains AI Assistant y que el plugin oficial actual de Claude Code (que hoy es básicamente un lanzador de terminal con hooks ligeros). Pensado para presentarlo a Anthropic.

El diferenciador es una **GUI nativa rica**: chat en streaming en un tool window, **review de diffs/changesets nativo** dirigido por el sistema de permisos, plan-mode, sesiones, y exponer la inteligencia del IDE (find-usages, diagnósticos, salida de tests) al agente.

---

## Decisión arquitectónica central

**El plugin NO usa Node ni la SDK de TypeScript en runtime.** Habla en Kotlin/JVM **directamente con el binario nativo `claude`** usando su protocolo `stream-json` + control sobre stdio.

Esto se verificó leyendo el código real de `@anthropic-ai/claude-agent-sdk` (v0.3.150, `claudeCodeVersion` 2.1.150). La "SDK" de TS es solo un wrapper finito que spawnea el binario `claude` y traduce NDJSON. El binario que empaqueta la SDK es **idéntico** (mismo BuildID) al `claude` que instala el usuario. Por tanto replicamos ese wrapper en Kotlin.

> La SDK de TS queda instalada en `node_modules/` **solo como referencia** de tipos y protocolo (`node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts`, `sdk-tools.d.ts`, `sdk.mjs`). No se distribuye con el plugin.

### Decisiones tomadas
1. **UI: Swing nativo** (no JCEF). Look 100% IDE, mejor rendimiento. Los diffs se muestran con el `DiffManager` nativo del IDE.
2. **Binario `claude` preinstalado, requerido.** El plugin lo detecta en `PATH` (`com.intellij.execution.configurations.PathEnvironmentVariableUtil.findInPath("claude")`) y en rutas típicas (`~/.local/bin/claude`). Si no está, **avisa con una notificación accionable y falla limpio** (no se intenta empaquetar ni descargar). Se valida versión mínima vía `claude --version`.
3. **Auth: se reutiliza la del binario.** El `claude` instalado ya está logueado (suscripción/OAuth) o usa `ANTHROPIC_API_KEY`. El plugin no gestiona credenciales en el MVP (opcional más adelante: API key vía `PasswordSafe`).

---

## El protocolo (stream-json + control)

Se lanza el binario una vez por sesión y se mantiene vivo (modo interactivo / streaming-input):

```
claude --output-format stream-json --verbose --input-format stream-json \
       --permission-prompt-tool stdio \
       [--model <m>] [--permission-mode <mode>] [--resume <sessionId>] [--continue] \
       [--allowedTools a,b] [--disallowedTools c] [--mcp-config <json>] \
       [--setting-sources=user,project,local] [--include-partial-messages] \
       [--agent <name>] [--thinking adaptive|disabled] [--fallback-model <m>]
```
- `cwd` = raíz del proyecto. `env` hereda el del IDE (PATH, HOME, ANTHROPIC_API_KEY).
- Se **escribe** en stdin NDJSON (`SDKUserMessage`) y se **lee** de stdout NDJSON (`SDKMessage` + frames de control). Una línea = un JSON.

### Entrada (stdin) — enviar un prompt del usuario
```json
{"type":"user","message":{"role":"user","content":"..."},"parent_tool_use_id":null}
```

### Salida (stdout) — tipos de mensaje a parsear
- `{"type":"system","subtype":"init", "session_id":"...", "mcp_servers":[...]}` → capturar `session_id` para `--resume`.
- `{"type":"assistant","message":{...BetaMessage con content blocks: text, tool_use...}}`
- `{"type":"stream_event", ...}` → deltas parciales (solo con `--include-partial-messages`).
- `{"type":"result","subtype":"success|error_*", "result":"...", "usage":{...}, "total_cost_usd":..., "permission_denials":[...]}` → fin de turno.
- `{"type":"keep_alive"}` → ignorar.
- Frames de control (ver abajo).

### Protocolo de control (bidireccional, correlado por `request_id`)
El **gancho clave** para el review de diffs. Cuando el agente quiere usar una tool que requiere permiso, el binario emite:
```json
{"type":"control_request","request_id":"r1",
 "request":{"subtype":"can_use_tool","tool_name":"Edit",
            "input":{"file_path":"/abs/x.py","old_string":"...","new_string":"..."},
            "title":"Claude wants to edit x.py","tool_use_id":"tu_1",
            "permission_suggestions":[...],"blocked_path":null}}
```
El host responde por stdin con un `PermissionResult`:
```json
{"type":"control_response","response":{"request_id":"r1","subtype":"success",
  "response":{"behavior":"allow","updatedInput":{...opcional si el user editó...}}}}
```
o denegar: `{"behavior":"deny","message":"...","interrupt":false}`.

- Otros subtipos de `control_request`: `mcp_message` (enruta JSON-RPC de MCP por el mismo canal), `hook_callback`, `elicitation`, `request_user_dialog`.
- **Requests que el host inicia** (host→binario, como `control_request` con su propio `request_id`): `interrupt`, `set_permission_mode`, `set_model`, `initialize`, `mcp_status`, `get_context_usage`, `read_file`. El binario contesta con `control_response`.
- `control_cancel_request` cancela un request pendiente por `request_id`.

### Quién escribe el archivo (importante)
Con `behavior:"allow"`, **el binario `claude` escribe el archivo en disco él mismo** (no el IDE). Por tanto:
1. En `can_use_tool` (Edit/Write), **antes** de aprobar, reconstruimos el contenido propuesto (Edit = aplicar `old_string`→`new_string` sobre el archivo en disco; Write = `content`) y lo abrimos como **diff en una pestaña del editor** (no en ventana/modal): `SimpleDiffRequest` → `SimpleDiffRequestChain` → `ChainDiffVirtualFile` → `FileEditorManager.getInstance(project).openFile(vFile, true)`. (Se usa `openFile`, **no** `DiffEditorTabFilesManager.showDiffFile`: este último respeta el ajuste global "diff in window" y abriría una ventana.) Lado izq = actual en disco, der = propuesto.
2. **Nada de diálogos modales.** La aprobación es una tarjeta inline en el tool window (`permissionTray` en `ChatPanel`) con botones Accept/Reject (y "View diff"), al estilo del JetBrains AI Assistant. El user aprueba/rechaza → mandamos `allow` (con `updatedInput` si editó) o `deny`; al resolver, se cierra la pestaña de diff.
3. Tras la escritura, el resultado de la tool (`FileEditOutput`/`FileWriteOutput`) trae `originalFile`, `structuredPatch` (hunks) y `gitDiff.patch` listos para pintar en el transcript.
4. **Refrescar el VFS** para que el IDE vea el cambio externo: `VfsUtil.markDirtyAndRefresh(true, false, false, file)` + `FileDocumentManager` recarga el `Document`.

---

## Arquitectura del plugin (Kotlin)

Servicios y clases (bajo `src/main/kotlin/dev/lain/claudejb/`):

- **`process/ClaudeBinaryLocator`** — encuentra/valida el binario `claude` (PATH + rutas típicas + versión). Si falta → `Notification` accionable y aborta.
- **`process/ClaudeProcess`** — `GeneralCommandLine` + `KillableColoredProcessHandler`; gestiona stdin/stdout, ciclo de vida, `interrupt`/kill graceful (stdin EOF → grace ~2s).
- **`protocol/`** — modelos de datos (`SdkMessage`, `ControlRequest`, `ControlResponse`, `PermissionResult`, tool inputs/outputs) + serialización JSON (kotlinx.serialization). Lector NDJSON por líneas desde stdout (`ProcessAdapter.onTextAvailable` o lectura de stream en `Dispatchers.IO`).
- **`session/ClaudeSession`** (clase **instanciable**, una por pestaña de chat) — mantiene su proceso, el `session_id`, cola de mensajes de entrada, y el modelo del transcript (observable para la UI). Métodos: `send(prompt)`, `interrupt()`, `setModel()`, `setPermissionMode()`, `resume(id)`.
- **`session/ChatSessionManager`** (`@Service(PROJECT)`) — dueño del conjunto de `ClaudeSession` abiertas (pestañas), sabe cuál es la **activa** (la usan Settings y los diálogos info) y las dispone con el proyecto. `create()`, `remove()`, `activeOrCreate()`.
- **`permission/PermissionBroker`** — recibe `can_use_tool` y **no bloquea** (jamás abre diálogos): decide si auto-aprobar (modo `bypassPermissions`/`acceptEdits`) o entregar un `PendingPermission` a la UI vía el callback `present`. La resolución real (escribir el `PermissionResult`) la hace `ClaudeSession.resolvePermission(requestId, allow)` cuando el user pulsa Accept/Reject en la tarjeta. `PendingPermission` vive en el paquete `permission`.
- **`ui/ClaudeToolWindowFactory`** + **`ui/ChatPanel`** (Swing) — tool window con **pestañas de chat** (una `Content` por `ClaudeSession`, cerrable; acción **New Chat** en la barra de título). `ChatPanel` = transcript en streaming + **composer tipo tarjeta** con las **opciones (modelo · modo · effort · thinking) como chips debajo del prompt** y botón Send. Render de bloques: texto, thinking, tool_use, diffs.
- **`context/EditorContextProvider`** — archivo/selección/caret actuales (`FileEditorManager`, `SelectionModel`), diagnósticos, para inyectar como contexto (@-mentions).
- **`diff/DiffPresenter`** — construye el `SimpleDiffRequest` (actual vs. propuesto) y lo abre **en el área de editores** como `ChainDiffVirtualFile` vía `FileEditorManager.openFile` (`openDiff` → devuelve el `VirtualFile`; `revealDiff`/`closeDiff` para enfocar/cerrar). Sin modales ni ventanas.
- **(V2) `mcp/IdeToolsServer`** — expone tools del IDE al agente (find-usages, diagnósticos, run/test output) vía `--mcp-config` apuntando a un server MCP local (stdio/http).

### plugin.xml — extension points
- `<toolWindow id="Claude Code" anchor="right" factoryClass="...ClaudeToolWindowFactory"/>`
- `<notificationGroup>` para avisos (binario no encontrado, errores).
- `<projectService>` para `ClaudeSessionService`.
- (más adelante) `<applicationConfigurable>`/`<projectConfigurable>` para settings, acciones con shortcuts (enviar selección, abrir Claude).

### Concurrencia / threading
- I/O del proceso y parseo en `Dispatchers.IO`. Actualizaciones de UI en `Dispatchers.EDT`/`invokeLater`.
- Lecturas del modelo PSI/VFS dentro de `ReadAction`/`readAction {}`; escrituras vía `WriteCommandAction` (solo si en algún flujo aplicamos cambios desde el IDE).
- `CoroutineScope` inyectado en el `@Service` (no usar scopes deprecados).

---

## Stack y build

- **IntelliJ Platform Gradle Plugin 2.16.0**: `id("org.jetbrains.intellij.platform") version "2.16.0"`. **Requiere Gradle ≥ 9.0** → el wrapper está fijado en **Gradle 9.5.1** (no 8.x).
- **Kotlin 2.1.20** (+ `kotlin("plugin.serialization")`), **toolchain JDK 21**. **21 es el máximo**: el IDE corre sobre JBR 21 y el bytecode del plugin no puede ser mayor. Gradle puede ejecutarse con JDK 21–25; aquí se usa un Temurin 21 en `~/.local/jdks/jdk-21.0.11+10` (el sistema trae JDK 25, demasiado nuevo para Gradle 8.x).
- Target: `intellijPlatform { create("IC", "2025.1") }`; `since-build=243`, `until-build=261.*` (carga en IDEA 2026.1).
- Runtime: `kotlinx-serialization-json:1.7.3`. Se excluyen `kotlin-stdlib`/`annotations` del bundle vía `configurations.runtimeClasspath` (los provee la plataforma; evita conflictos y warnings del verifier).
- Build/run: `JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 ./gradlew buildPlugin` → zip en `build/distributions/`. `verifyPlugin` (configurado contra el IDEA local instalado). `runIde` para un IDE de pruebas.

---

## MVP (hito 1) — confirmado

1. Tool window "Claude Code" con **chat en streaming** (Swing).
2. **Detección del binario** `claude` + aviso/fallo si falta.
3. **Contexto del editor**: enviar archivo/selección actuales como contexto.
4. **Ediciones revisadas**: interceptar `Edit`/`Write` vía `can_use_tool` → **diff nativo** → aprobar/rechazar → refrescar VFS.
5. Sesión persistente con `session_id` + `--resume`.

### Roadmap posterior
- **V2**: plan-mode (`--permission-mode plan` + tool `ExitPlanMode`), MCP de herramientas del IDE (find-usages, diagnósticos, tests), "always allow" persistente, selector de modelo + thinking.
- **V3**: edits inline (ghost text), integración con VCS/changelist (aplicar changeset como revisión), tests/debugger como contexto.

---

## Estado actual del repo (MVP + GUI completos)
- Paquete raíz: **`dev.lain.claudejb`**. Plugin id: `dev.lain.claude-code-for-jetbrains`.
- Implementado: scaffold Gradle + `plugin.xml` + **icono** (tool window `anchor="right"`, en el stripe junto al AI Assistant) + `notificationGroup` + `projectConfigurable`; **protocolo** (`protocol/`, verificado contra la SDK), **transporte** (`process/`), **sesión multi-chat** (`session/`: `ClaudeSession` instanciable + `ChatSessionManager`, cola **multiprompt** + handshake `initialize`), **permisos + diff nativo** (`permission/`, `diff/`), **contexto** (`context/`), y **GUI** (`ui/`: tool window con **pestañas de chat** + **New Chat**, `ChatPanel` con composer tipo tarjeta y **opciones como chips bajo el prompt**, paleta de **todos** los slash commands, diálogos contexto/coste/MCP/agents, Settings).
- **Compila limpio**; `verifyPlugin` → **Compatible** contra IU-261.24374.151 (IDEA Ultimate 2026.1), 0 problemas. Artefacto: `build/distributions/claude-code-for-jetbrains-0.1.0.zip`.
- `claude` 2.1.150 en `~/.local/bin/claude`. `package.json` + `node_modules/` = SDK de referencia (no runtime).

## Correcciones de protocolo verificadas (vs. lo que decía antes este doc)
- **`--print` es OBLIGATORIO** junto a `stream-json` input/output e `--include-partial-messages`. El proceso sigue vivo porque lee stdin en streaming.
- `--permission-prompt-tool stdio` confirmado (la SDK hace `push("--permission-prompt-tool","stdio")`).
- El handshake de control `initialize` devuelve `commands` (`SlashCommand`: name/description/argumentHint/aliases), `models`, `agents`, `available_output_styles`, `account`. `system/init` trae `slash_commands` (solo nombres).
- **`system/init` es PEREZOSO en 2.1.150**: el binario NO lo emite al arrancar, solo **tras el primer mensaje de usuario** (verificado con los flags reales; con solo `initialize` responde el `control_response` pero nunca `init`). ⇒ **No bloquear la sesión esperando `Init`**: era un deadlock (`pump()` esperaba `ready`, `ready` esperaba `Init`, `Init` esperaba un prompt) que dejaba el plugin colgado en "Starting Claude Code…". Ahora `ClaudeSession.start()` marca `ready=true` en cuanto arranca el proceso (el binario acepta stdin desde el inicio); `Init`, cuando llega, solo rellena `sessionId`/modelo. También aparecen líneas nuevas no documentadas: `system/session_state_changed` y `rate_limit_event` (se ignoran como `Other`).
- **`AskUserQuestion` va por `can_use_tool`, NO por `request_user_dialog`** (verificado empíricamente lanzando el binario con los flags reales). Llega como `can_use_tool` con `tool_name:"AskUserQuestion"` e `input:{questions:[{question,header,options:[{label,description,preview?}],multiSelect}]}`. El host renderiza las preguntas y responde **`allow` con `updatedInput = {...input, "answers":{textoPregunta: label}}`** (labels separados por comas si `multiSelect`); el binario refleja la elección en el `tool_result` (`mapToolResultToToolResultBlockParam`). Si se responde `allow` SIN `answers`, el resultado sale vacío y el modelo improvisa. El `request_user_dialog`/Ink (`setToolJSX`) es solo para el modo terminal interactivo. Implementado en `PermissionBroker` (branch que nunca auto-aprueba) + `ClaudeSession.resolveQuestion` + tarjeta `questionCard` en `ChatPanel`.

## Cómo continuar
- Instalar a mano: IDEA → Settings → Plugins → ⚙ → *Install Plugin from Disk…* → el zip de `build/distributions/`. O `JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 ./gradlew runIde`.
- Roadmap: render markdown/code en el transcript, autocompletado @-file, "always allow" persistente de permisos, MCP de herramientas del IDE, edits inline.
- Mantener este `CLAUDE.md` actualizado cuando cambie la arquitectura o las decisiones.

## Referencias
- Tipos/protocolo (fuente de verdad local): `node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts`, `sdk-tools.d.ts`, `sdk.mjs` (`initialize()` arma los flags; `processControlRequest`/`readMessages` manejan el control).
- Docs Agent SDK: https://code.claude.com/docs/en/agent-sdk/overview · TS reference, permissions, mcp, sessions, hooks bajo `/agent-sdk/`.
- IntelliJ Platform SDK: https://plugins.jetbrains.com/docs/intellij/ (Gradle plugin 2.x, tool windows, DiffManager, threading, execution, persisting-sensitive-data).
- Plugin oficial actual de Claude Code (referencia de paridad): https://code.claude.com/docs/en/jetbrains y la extensión de VS Code para features a igualar/superar.
