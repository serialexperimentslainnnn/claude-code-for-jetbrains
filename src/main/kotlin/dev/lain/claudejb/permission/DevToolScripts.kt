package dev.lain.claudejb.permission

object DevToolScripts {

    private val KNOWN_NAMES: Set<String> = setOf(
        "gradlew", "gradlew.bat", "gradle", "mvnw", "mvnw.cmd", "mvn", "ant",
        "sbt", "sbtx", "bazel", "bazelisk", "buck", "buck2", "pants", "lein", "boot",
        "make", "cmake", "ninja", "meson", "scons", "configure", "bootstrap", "autogen.sh",
        "npm", "npx", "yarn", "pnpm", "pnpx", "bun", "bunx", "node", "corepack", "deno",
        "tsc", "tsx", "ts-node", "vite", "webpack", "rollup", "esbuild", "turbo", "nx",
        "eslint", "prettier", "jest", "vitest", "playwright", "cypress",
        "python", "python3", "pip", "pip3", "pipx", "poetry", "uv", "uvx", "pipenv", "conda", "mamba",
        "pytest", "tox", "nox", "ruff", "black", "mypy", "pyright", "flake8", "isort", "pylint",
        "alembic", "django-admin", "gunicorn", "uvicorn", "hatch", "pdm",
        "cargo", "rustup", "rustc", "clippy-driver",
        "go", "gofmt", "goimports", "golangci-lint", "dlv",
        "bundle", "bundler", "rake", "rails", "rspec", "rubocop",
        "composer", "phpunit", "phpstan",
        "dotnet", "nuget", "msbuild",
        "kotlinc", "kotlin", "ktlint", "detekt", "javac", "java", "jshell", "jlink", "jpackage",
        "terraform", "terragrunt", "tofu", "pulumi", "packer", "vagrant",
        "kubectl", "helm", "kustomize", "skaffold", "kubectx", "kubens", "k9s", "argocd", "flux", "kind", "minikube",
        "docker", "docker-compose", "podman", "podman-compose", "buildah", "skopeo", "nerdctl",
        "ansible", "ansible-playbook", "ansible-galaxy", "aws", "gcloud", "az", "doctl", "flyctl", "heroku",
        "gh", "glab", "git-lfs", "pre-commit",
    )

    private val KNOWN_DIRS: List<String> = listOf(
        "/node_modules/.bin/",
        "/.venv/bin/", "/venv/bin/", "/.venv/scripts/", "/venv/scripts/",
        "/vendor/bin/",
        "/gradle/wrapper/",
        "/.tox/", "/.nox/",
    )

    internal fun isKnownDevTool(path: String): Boolean {
        val lower = path.replace('\\', '/').lowercase()
        if (KNOWN_DIRS.any { it in lower }) return true
        return lower.substringAfterLast('/') in KNOWN_NAMES
    }
}
