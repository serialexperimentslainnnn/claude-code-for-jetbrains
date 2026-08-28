package dev.lain.claudejb.headless

import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.LibraryData
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Method

class GradleDependencyModelHeadlessTest : BasePlatformTestCase() {

    fun `test the external system project data api is on this plugin's classpath already`() {
        assertNotNull(ProjectDataManager.getInstance())
        assertEquals("GRADLE", ProjectSystemId("GRADLE").id)
        assertNotNull(ProjectKeys.LIBRARY_DEPENDENCY)

        report("ProjectDataManager", ProjectDataManager.getInstance().javaClass.name)
        report("LIBRARY_DEPENDENCY key", ProjectKeys.LIBRARY_DEPENDENCY.dataType)
    }

    fun `test a library dependency node carries a scope and names its library`() {
        val scope = LibraryDependencyData::class.java.getMethod("getScope").assertNotDeprecated()
        assertEquals(
            "LibraryDependencyData.getScope no longer returns DependencyScope. That enum is the only scope " +
                "signal the IDE model carries, and the prod-vs-dev split of the inventory is derived from it.",
            "com.intellij.openapi.roots.DependencyScope",
            scope.returnType.name,
        )

        val externalName = LibraryData::class.java.getMethod("getExternalName").assertNotDeprecated()
        assertEquals(String::class.java, externalName.returnType)

        report("LibraryDependencyData.getScope", scope.returnType.name)
    }

    fun `test nothing in the library dependency model distinguishes direct from transitive`() {
        val suspects = LibraryDependencyData::class.java.methods
            .map { it.name }
            .filter { name -> TRANSITIVITY_WORDS.any { it in name.lowercase() } }
            .sorted()

        assertEquals(
            "LibraryDependencyData grew something that looks like a transitivity marker: $suspects. If it " +
                "really answers 'did a build file ask for this', the Vulnerabilities view can stop reporting " +
                "every Gradle and Maven component as origin UNKNOWN.",
            emptyList<String>(),
            suspects,
        )

        report("direct-vs-transitive", "absent from LibraryDependencyData")
    }

    fun `test the module model exposes the same resolved list without an import refresh`() {
        LibraryOrderEntry::class.java.getMethod("getLibraryName").assertNotDeprecated()
        LibraryOrderEntry::class.java.getMethod("getScope").assertNotDeprecated()
        LibraryOrderEntry::class.java.getMethod("isExported").assertNotDeprecated()

        assertEquals(
            "DependencyScope changed shape; the prod-vs-dev split of the inventory is derived from its names.",
            listOf("COMPILE", "PROVIDED", "RUNTIME", "TEST"),
            DependencyScope.entries.map { it.name }.sorted(),
        )
    }

    fun `test the api answers empty for a project that was never imported`() {
        val data = ProjectDataManager.getInstance().getExternalProjectsData(project, ProjectSystemId("GRADLE"))
        val modules = ModuleManager.getInstance(project).modules
        val libraries = modules.flatMap { module ->
            ModuleRootManager.getInstance(module).orderEntries
                .filterIsInstance<LibraryOrderEntry>()
                .mapNotNull { it.libraryName }
        }

        report("fixture modules", modules.joinToString { it.name })
        report("fixture external project infos", data.size.toString())
        report("fixture library entries", libraries.joinToString().ifEmpty { "none" })

        assertTrue(
            "A BasePlatformTestCase fixture reported Gradle data (infos=${data.size}, libraries=$libraries). " +
                "The fixture never had an import, so either it changed or this test is reading a real " +
                "project. The whole finding rests on this model existing only after an import, so re-read " +
                "the verdict before trusting either.",
            data.isEmpty() && libraries.none { it.startsWith(GRADLE_PREFIX) },
        )
    }

    fun `test a gradle library name yields a coordinate only after the synthetic ones are refused`() {
        assertEquals(
            "The 'Gradle: group:artifact:version' shape is what makes an IDE import queryable against OSV " +
                "without invoking the build tool. Transitives are in this list and are indistinguishable " +
                "from the one dependency this repository's build file actually declares.",
            listOf(
                Triple("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.7.3"),
                Triple("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.7.3"),
                Triple("org.jetbrains", "annotations", "13.0"),
                Triple("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.20"),
            ),
            OBSERVED.mapNotNull { coordinateOf(it) },
        )

        assertEquals(
            "The IntelliJ Platform Gradle plugin mints synthetic library names that split into three parts " +
                "exactly like a Maven coordinate, so structure alone does not tell them apart. The prefix " +
                "denylist is load-bearing: without it the IDE ships 'bundledModule' to OSV as a groupId.",
            listOf(
                "Gradle: bundledModule:intellij.platform.backend:IU-253.29346.138",
                "Gradle: bundledPlugin:Git4Idea:IU-253.29346.138",
            ),
            OBSERVED.filter { coordinateOf(it) == null },
        )
    }

    private fun coordinateOf(name: String): Triple<String, String, String>? {
        if (!name.startsWith(GRADLE_PREFIX)) return null
        val parts = name.removePrefix(GRADLE_PREFIX).split(':')
        if (parts.size != 3 || parts[0] in SYNTHETIC_GROUPS) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun report(label: String, value: String) = println("[gradle-model] $label = $value")

    private fun Method.assertNotDeprecated(): Method = apply {
        assertFalse(
            "$declaringClass.$name is deprecated — the Vulnerabilities view would inherit the deprecation.",
            isAnnotationPresent(java.lang.Deprecated::class.java) || isAnnotationPresent(Deprecated::class.java),
        )
    }

    private companion object {
        const val GRADLE_PREFIX = "Gradle: "

        val SYNTHETIC_GROUPS = setOf("bundledModule", "bundledModuleV2", "bundledPlugin", "localIde")

        val TRANSITIVITY_WORDS = listOf("transitive", "direct", "declared", "requested")

        val OBSERVED = listOf(
            "Gradle: org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3",
            "Gradle: bundledModule:intellij.platform.backend:IU-253.29346.138",
            "Gradle: org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3",
            "Gradle: bundledPlugin:Git4Idea:IU-253.29346.138",
            "Gradle: org.jetbrains:annotations:13.0",
            "Gradle: org.jetbrains.kotlin:kotlin-stdlib:2.0.20",
        )
    }
}
