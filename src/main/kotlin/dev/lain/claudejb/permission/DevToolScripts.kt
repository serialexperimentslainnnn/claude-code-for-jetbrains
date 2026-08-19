package dev.lain.claudejb.permission

/**
 * **The build wrappers and tool entrypoints a developer runs all day, which the guard must not treat as
 * adversarial shell.**
 *
 * ### The problem this solves, and it is not a matter of taste
 * [ScriptExecution] hands `SensitiveGuard` the files a call runs, and the guard reads each one and judges its
 * contents with the whole rule set. That is right for a script the agent just wrote. It is wrong for
 * `./gradlew`, and the reason is structural rather than a question of severity: **a build wrapper is made of
 * exactly the things the rules look for.** Every real one probes with `command -v java >/dev/null 2>&1`, assigns
 * `JAVACMD=$JAVA_HOME/bin/java`, and quotes half its body — so reading it produces, in order, a raw system device
 * (`/dev/null`), an unreviewed file write (a `>` whose target after quote-stripping is a brace), and a script it
 * cannot read (`/java`, a path assembled by the guard's own expansion out of an assignment that was never a
 * command).
 *
 * None of those is a finding. All three are the analysis meeting a file it was not designed for, and the user
 * experiences them as the plugin refusing to build their own project — with no lever, because
 * [SecurityRule.SYSTEM_DEVICE] is not whitelistable and the command whitelist cannot lift a wall.
 *
 * ### What the exemption actually is
 * **A file on this list is not READ.** Its contents are not judged, so it produces no hit of any kind. It is the
 * narrowest thing that works: the file stops being an input to the analysis rather than the analysis being
 * loosened.
 *
 * ### What it does NOT do — and this is the half that keeps it honest
 *  - **It changes nothing about the COMMAND the agent issued.** Every rule still runs at depth 0 on the text of
 *    the call itself. `terraform` and `kubectl` are on this list as *tool entrypoints*, and
 *    `terraform destroy` is refused exactly as before by [SecurityRule.DESTRUCTIVE_IAC] — that rule matches the
 *    command line, never the tool's own file. Same for `docker system prune`, `aws s3 rb --force`,
 *    `git push --force` and every other destructive vector.
 *  - **It does not exempt what the wrapper is handed.** `bash ./gradlew` skips `gradlew`; `python3 evil.py` still
 *    reads and judges `evil.py`, because the argument is not on this list.
 *  - **It is a list of NAMES, and a name is chosen by whoever creates the file.** That is the exposure, stated
 *    rather than discovered: an attacker who can put a file called `gradlew` in the project gets it unread. What
 *    bounds it is that **creating that file is itself a wall** — writing it through the shell is
 *    [SecurityRule.SHELL_FILE_WRITE], and writing it through `Write`/`Edit` is a reviewable diff the user sees.
 *    So the exemption only ever covers files that were already there, which is the population it is for.
 *  - **It contains no location.** `.git/hooks/…` is deliberately absent (spelled with an ellipsis rather than a
 *    star, since a slash followed by a star opens a nested block comment and leaves this KDoc unclosed): a git
 *    hook is a persistence mechanism
 *    ([SecurityRule.PERSISTENCE_MECHANISM]) and being a well-known name is exactly what makes it useful to an
 *    attacker.
 */
object DevToolScripts {

    /**
     * Build wrappers and tool entrypoints, by **basename**, lower-cased.
     *
     * Everything here is a program a developer or an operator runs deliberately and repeatedly, shipped by the
     * ecosystem rather than written for this session. A name is on the list because reading its body tells the
     * guard nothing true — not because the tool is harmless: several of these are precisely the tools the
     * destructive family exists for, and that family is untouched (see the class doc).
     */
    private val KNOWN_NAMES: Set<String> = setOf(
        // ── build wrappers and build systems ──────────────────────────────────────────────────────────────
        "gradlew", "gradlew.bat", "gradle", "mvnw", "mvnw.cmd", "mvn", "ant",
        "sbt", "sbtx", "bazel", "bazelisk", "buck", "buck2", "pants", "lein", "boot",
        "make", "cmake", "ninja", "meson", "scons", "configure", "bootstrap", "autogen.sh",
        // ── JavaScript / TypeScript ───────────────────────────────────────────────────────────────────────
        "npm", "npx", "yarn", "pnpm", "pnpx", "bun", "bunx", "node", "corepack", "deno",
        "tsc", "tsx", "ts-node", "vite", "webpack", "rollup", "esbuild", "turbo", "nx",
        "eslint", "prettier", "jest", "vitest", "playwright", "cypress",
        // ── Python ────────────────────────────────────────────────────────────────────────────────────────
        "python", "python3", "pip", "pip3", "pipx", "poetry", "uv", "uvx", "pipenv", "conda", "mamba",
        "pytest", "tox", "nox", "ruff", "black", "mypy", "pyright", "flake8", "isort", "pylint",
        "alembic", "django-admin", "gunicorn", "uvicorn", "hatch", "pdm",
        // ── Rust / Go / Ruby / PHP / .NET ─────────────────────────────────────────────────────────────────
        "cargo", "rustup", "rustc", "clippy-driver",
        "go", "gofmt", "goimports", "golangci-lint", "dlv",
        "bundle", "bundler", "rake", "rails", "rspec", "rubocop",
        "composer", "phpunit", "phpstan",
        "dotnet", "nuget", "msbuild",
        // ── JVM tooling ───────────────────────────────────────────────────────────────────────────────────
        "kotlinc", "kotlin", "ktlint", "detekt", "javac", "java", "jshell", "jlink", "jpackage",
        // ── devops / infrastructure entrypoints ───────────────────────────────────────────────────────────
        // On the list as PROGRAMS whose own file is not worth reading. Every dangerous thing they can be ASKED
        // to do is matched on the command line by the destructive family, which this does not touch.
        "terraform", "terragrunt", "tofu", "pulumi", "packer", "vagrant",
        "kubectl", "helm", "kustomize", "skaffold", "kubectx", "kubens", "k9s", "argocd", "flux", "kind", "minikube",
        "docker", "docker-compose", "podman", "podman-compose", "buildah", "skopeo", "nerdctl",
        "ansible", "ansible-playbook", "ansible-galaxy", "aws", "gcloud", "az", "doctl", "flyctl", "heroku",
        "gh", "glab", "git-lfs", "pre-commit",
    )

    /**
     * Directories whose contents are tool entrypoints by construction — a package manager or a virtualenv put
     * them there, which is a boundary the agent cannot move by choosing a filename.
     *
     * Matched as a path SEGMENT sequence so it cannot be satisfied by a lookalike directory name elsewhere in the
     * path, and deliberately short: each entry is a place whose whole purpose is holding executables the project's
     * own manifest installed.
     */
    private val KNOWN_DIRS: List<String> = listOf(
        "/node_modules/.bin/",
        "/.venv/bin/", "/venv/bin/", "/.venv/scripts/", "/venv/scripts/",
        "/vendor/bin/",
        "/gradle/wrapper/",
        "/.tox/", "/.nox/",
    )

    /**
     * Is [path] a known development or devops tool — i.e. a file whose body the guard should not read?
     *
     * [path] arrives already normalised and folded (see `SensitiveGuard.scriptFindings`), so the comparison is on
     * the real location rather than on how it was spelled.
     */
    internal fun isKnownDevTool(path: String): Boolean {
        val lower = path.replace('\\', '/').lowercase()
        if (KNOWN_DIRS.any { it in lower }) return true
        return lower.substringAfterLast('/') in KNOWN_NAMES
    }
}
