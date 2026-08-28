package dev.lain.claudejb.vuln

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VulnInventoryTest {

    private fun parse(kind: ManifestKind, text: String) = VulnInventory.parse(kind, text, kind.fileName)

    private fun originOf(components: List<VulnComponent>, name: String): ComponentOrigin? =
        components.firstOrNull { it.name == name }?.origin

    @Test
    fun `an npm lockfile separates what the project asked for from what came with it`() {
        val text = """
            {
              "lockfileVersion": 3,
              "packages": {
                "": { "dependencies": { "left-pad": "^1.0.0" }, "devDependencies": { "vitest": "^3.0.0" } },
                "node_modules/left-pad": { "version": "1.3.0" },
                "node_modules/vitest": { "version": "3.2.4" },
                "node_modules/tinypool": { "version": "1.1.1" }
              }
            }
        """.trimIndent()

        val components = parse(ManifestKind.NPM_LOCK, text)

        assertEquals(3, components.size)
        assertEquals(ComponentOrigin.DIRECT, originOf(components, "left-pad"))
        assertEquals(ComponentOrigin.DIRECT, originOf(components, "vitest"))
        assertEquals(ComponentOrigin.TRANSITIVE, originOf(components, "tinypool"))
        assertTrue(components.all { it.ecosystem == "npm" })
    }

    @Test
    fun `a workspace link is not a published package and is left out`() {
        val text = """
            {
              "packages": {
                "": { "dependencies": { "app": "*" } },
                "packages/app": { "version": "0.0.0" },
                "node_modules/app": { "resolved": "packages/app", "link": true },
                "node_modules/real": { "version": "2.0.0" }
              }
            }
        """.trimIndent()

        val components = parse(ManifestKind.NPM_LOCK, text)

        assertEquals(listOf("real"), components.map { it.name })
    }

    @Test
    fun `a version 1 lockfile cannot say which dependency is direct, so it says unknown`() {
        val text = """
            {
              "lockfileVersion": 1,
              "dependencies": {
                "left-pad": { "version": "1.3.0", "dependencies": { "nested": { "version": "0.1.0" } } }
              }
            }
        """.trimIndent()

        val components = parse(ManifestKind.NPM_LOCK, text)

        assertEquals(2, components.size)
        assertTrue(components.all { it.origin == ComponentOrigin.UNKNOWN })
    }

    @Test
    fun `a requirements file yields only what it pins exactly, and never claims to know the origin`() {
        val text = """
            # a comment
            -r other.txt
            requests==2.32.3
            urllib3[socks]==2.2.2 ; python_version >= "3.9"
            flask>=3.0
            git+https://example.invalid/pkg.git#egg=pkg
        """.trimIndent()

        val components = parse(ManifestKind.PIP_REQUIREMENTS, text)

        assertEquals(listOf("requests", "urllib3"), components.map { it.name })
        assertEquals(listOf("2.32.3", "2.2.2"), components.map { it.version })
        assertTrue(components.all { it.origin == ComponentOrigin.UNKNOWN })
        assertTrue(components.all { it.ecosystem == "PyPI" })
    }

    @Test
    fun `a cargo lockfile is read package by package and does not swallow the tables after it`() {
        val text = """
            version = 3

            [[package]]
            name = "serde"
            version = "1.0.210"

            [[package]]
            name = "syn"
            version = "2.0.79"
            dependencies = ["proc-macro2"]

            [metadata]
            name = "not-a-package"
        """.trimIndent()

        val components = parse(ManifestKind.CARGO_LOCK, text)

        assertEquals(listOf("serde", "syn"), components.map { it.name })
        assertEquals(listOf("1.0.210", "2.0.79"), components.map { it.version })
        assertTrue(components.all { it.origin == ComponentOrigin.UNKNOWN })
        assertTrue(components.all { it.ecosystem == "crates.io" })
    }

    @Test
    fun `go mod marks an indirect requirement transitive and everything else direct`() {
        val text = """
            module example.invalid/app

            go 1.23

            require github.com/spf13/cobra v1.8.1

            require (
                github.com/stretchr/testify v1.9.0
                golang.org/x/sys v0.25.0 // indirect
            )

            replace (
                github.com/spf13/cobra => ./vendored v9.9.9
            )
        """.trimIndent()

        val components = parse(ManifestKind.GO_MOD, text)

        assertEquals(3, components.size)
        assertEquals(ComponentOrigin.DIRECT, originOf(components, "github.com/spf13/cobra"))
        assertEquals(ComponentOrigin.DIRECT, originOf(components, "github.com/stretchr/testify"))
        assertEquals(ComponentOrigin.TRANSITIVE, originOf(components, "golang.org/x/sys"))
        assertNull(originOf(components, "./vendored"), "a replace block is not a requirement")
        assertTrue(components.all { it.ecosystem == "Go" })
    }

    @Test
    fun `collecting walks the project, names each manifest and never descends into node_modules`(
        @TempDir root: File,
    ) {
        File(root, "package-lock.json").writeText(
            """{"packages":{"":{"dependencies":{"left-pad":"^1"}},"node_modules/left-pad":{"version":"1.3.0"}}}""",
        )
        File(root, "node_modules/deep").mkdirs()
        File(root, "node_modules/deep/package-lock.json").writeText(
            """{"packages":{"node_modules/hidden":{"version":"9.9.9"}}}""",
        )
        File(root, "service").mkdirs()
        File(root, "service/requirements.txt").writeText("requests==2.32.3\n")

        val components = VulnInventory.collect(root)

        assertEquals(setOf("left-pad", "requests"), components.map { it.name }.toSet())
        assertEquals(
            setOf("package-lock.json", "service/requirements.txt"),
            components.map { it.manifest }.toSet(),
        )
    }

    @Test
    fun `the same package pinned by two manifests is asked about once`(@TempDir root: File) {
        File(root, "requirements.txt").writeText("requests==2.32.3\n")
        File(root, "service").mkdirs()
        File(root, "service/requirements.txt").writeText("requests==2.32.3\n")

        assertEquals(1, VulnInventory.collect(root).size)
    }
}
