# Claude Code for JetBrains — plugin nativo

Plugin de IntelliJ Platform (Kotlin/Swing) que integra Claude Code en IDEs JetBrains, con GUI nativa rica (chat en streaming, review de diffs por permisos, plan-mode, sesiones, inteligencia del IDE al agente). Objetivo: superar al AI Assistant y al plugin oficial (hoy un lanzador de terminal). Para presentar a Anthropic.

## Decisión central
**No usa Node ni la SDK de TS en runtime.** Habla Kotlin/JVM **directo con el binario `claude`** vía `stream-json` + control sobre stdio. La SDK de TS es solo un wrapper que spawnea el mismo binario; la replicamos en Kotlin. `node_modules/@anthropic-ai/claude-agent-sdk/` (`sdk.d.ts`, `sdk-tools.d.ts`, `sdk.mjs`) queda **solo como referencia** del protocolo, no se distribuye.

Decisiones: (1) **Swing**, no JCEF; diffs con el `DiffManager` del IDE. (2) **Binario `claude` preinstalado y requerido** (detección PATH + `~/.local/bin`; si falta → notificación accionable y aborta). (3) **Auth reutilizada** del binario (suscripción/OAuth o `ANTHROPIC_API_KEY`).

**Principio de comportamiento:** paridad de UX con el Claude Code original, consumiendo el **protocolo estructurado** de la SDK (`stream-json`/control) — "usar la SDK" = su contrato, no el paquete npm. **Nunca planchar el output del CLI**: no volcar el texto formateado del terminal; reconstruir cada estado/comando de forma **nativa** desde los campos estructurados del evento (p.ej. compactación desde `status`/`compact_metadata`, coste desde `get_session_cost`). `system/local_command_output` es el antipatrón a evitar.

## Protocolo (stream-json + control)
Un proceso por sesión, vivo en streaming-input. Flags clave (`--print` es **obligatorio**):
```
claude --print --output-format stream-json --input-format stream-json --verbose \
       --permission-prompt-tool stdio [--include-partial-messages] [--permission-mode <m>] \
       [--model <m>] [--resume <id>] [--allowedTools …] [--setting-sources user,project,local]
```
- stdin: `{"type":"user","message":{"role":"user","content":"…"},"parent_tool_use_id":null}` (una línea = un JSON). `cwd`=raíz del proyecto, `env` heredado.
- stdout: `system/init` (trae `session_id`, `slash_commands`), `assistant` (content blocks), `stream_event` (deltas), `result` (fin de turno), `keep_alive` (ignorar), frames de control.
- **Control** (correlado por `request_id`): el binario emite `control_request{subtype:"can_use_tool",tool_name,input,title}` → el host responde `control_response{subtype:"success",response:{behavior:"allow",updatedInput}}` o `{behavior:"deny",message}`. Host→binario: `initialize`, `interrupt`, `set_model`, `set_permission_mode`, `get_context_usage`, `get_session_cost`, `mcp_status`.

**Quién escribe el archivo:** con `allow`, **el binario escribe** (no el IDE). Por eso, antes de aprobar Edit/Write reconstruimos el contenido propuesto y abrimos un **diff en pestaña de editor** (`SimpleDiffRequest`→`ChainDiffVirtualFile`→`FileEditorManager.openFile`; NO `DiffEditorTabFilesManager.showDiffFile`, que abre ventana). Aprobación = **tarjeta inline no-modal** (Accept/Reject/View diff), nunca diálogos. Tras escribir, refrescar VFS (`VfsUtil.markDirtyAndRefresh`).

## Arquitectura (`src/main/kotlin/dev/lain/claudejb/`)
- `process/` — `ClaudeBinaryLocator` (localiza/valida) + `ClaudeProcess` (GeneralCommandLine + KillableColoredProcessHandler, stdio, kill graceful).
- `protocol/` — modelos kotlinx.serialization + `ProtocolParser` (NDJSON→`ClaudeEvent`) + `ControlProtocol` (builders de salida). Decodificación lenient (ignoreUnknownKeys).
- `session/` — `ClaudeSession` (instanciable, una por pestaña: proceso, `session_id`, cola, transcript observable, permisos, rate-limit, tokens en vivo) + `ChatSessionManager` (`@Service(PROJECT)`, dueño de las pestañas).
- `permission/PermissionBroker` — recibe `can_use_tool`, **no bloquea**; auto-aprueba (bypass/acceptEdits) o entrega `PendingPermission` a la UI. Resolución real en `ClaudeSession.resolvePermission`/`resolveQuestion`.
- `diff/DiffPresenter` — `openDiff`/`revealDiff`/`closeDiff` en el área de editores (sin modales).
- `context/EditorContextProvider` — archivo/selección/diagnósticos para @-mentions.
- `ui/` — `ClaudeToolWindowFactory` (pestañas + New Chat) + `ChatPanel` (transcript streaming + composer con chips modelo·modo·effort·thinking + barra de cuota + spinner/tokens) + `ChatMessageViews`/`TranscriptView`/`MarkdownRenderer`/`ChatTheme` + `CommandPalette` + `OptionMenus` + `InfoDialogs` + Settings.
- (V2) `mcp/IdeToolsServer` — exponer tools del IDE vía `--mcp-config`.

Threading: I/O y parseo en `Dispatchers.IO`; UI en EDT/`invokeLater`. plugin.xml: `toolWindow id="Claude Code"` anchor=right, `notificationGroup`, `projectService`, `projectConfigurable`.

## Stack y build
IntelliJ Platform Gradle Plugin **2.16.0** (requiere Gradle ≥9 → wrapper en **9.5.1**). Kotlin **2.1.20** + serialization, toolchain **JDK 21** (máximo: el IDE corre sobre JBR 21). Target `IC 2025.1`, since=243 until=261.*. Runtime: `kotlinx-serialization-json:1.7.3` (stdlib/annotations excluidos del bundle, los da la plataforma).
Build: `JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 ./gradlew buildPlugin` → zip en `build/distributions/`. También `verifyPlugin`, `runIde`. Instalar: Settings → Plugins → ⚙ → Install Plugin from Disk.

## Estado
Paquete `dev.lain.claudejb`, plugin id `dev.lain.claude-code-for-jetbrains`, versión 0.1.1. MVP + GUI completos y compilando limpio (`verifyPlugin` Compatible con IU-261). Implementado: protocolo+transporte, multi-chat con cola, permisos+diff nativo, AskUserQuestion, tablas markdown, auto-diff en acceptEdits/bypass, comandos multilínea, Ctrl+O reasoning, barra de cuota + spinner/tokens, menús que cierran al elegir, `/btw`, UI retematizada al tema del IDE. `claude` 2.1.150 en `~/.local/bin/claude`.

Pendiente: fix del reset a `default` (la línea de `ClaudeSession` que adopta `permissionMode` del `init`), persistir el modo del chip, MCP de tools del IDE, "always allow" persistente, edits inline.

## Hechos de protocolo verificados (empíricamente, 2.1.150)
- `--print` obligatorio junto a stream-json in/out. `--permission-prompt-tool stdio` confirmado.
- `initialize` (handshake) devuelve `commands`/`models`/`agents`/`available_output_styles`/`account`. **No** incluye `/btw` (es client-side del REPL, interceptado por regex) → el plugin lo añade a la paleta y lo envía con `sendSideQuestion`.
- `system/init` llega **en cada turno** (tras cada user message), reportando el `permissionMode` actual. NO bloquear la sesión esperándolo (deadlock histórico): `start()` marca `ready=true` al arrancar.
- **`AskUserQuestion` va por `can_use_tool`** (no `request_user_dialog`): `input:{questions:[{question,header,options:[{label,description,preview?}],multiSelect}]}`; el host responde `allow` con `updatedInput={...input,"answers":{pregunta:label}}` (coma si multiSelect). Sin `answers`, sale vacío y el modelo improvisa.
- `rate_limit_event` trae `status`/`resetsAt`/`rateLimitType`/`isUsingOverage`; `utilization` (% cuota) **solo cerca del límite**. `get_session_cost` → `{text}` con coste en **$** (API), no % de cuota.
- Tokens en vivo: `stream_event` `message_delta.usage.output_tokens` (acumulado del mensaje).
- El binario **acumula** varios user messages recibidos de golpe/mid-turn y los procesa juntos (contexto compartido) → la cola se vacía entera, no uno por turno.

## Referencias
- Protocolo (verdad local): `node_modules/@anthropic-ai/claude-agent-sdk/{sdk.d.ts,sdk-tools.d.ts,sdk.mjs}`.
- Docs: https://code.claude.com/docs/en/agent-sdk/overview · IntelliJ Platform SDK https://plugins.jetbrains.com/docs/intellij/ · plugin oficial https://code.claude.com/docs/en/jetbrains.
