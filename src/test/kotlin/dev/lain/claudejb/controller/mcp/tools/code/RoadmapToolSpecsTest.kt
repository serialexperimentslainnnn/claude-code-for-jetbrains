package dev.lain.claudejb.controller.mcp.tools.code

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RoadmapToolSpecsTest {

    @Test
    fun `the tool names are pinned per domain, and only the writers say they mutate`() {
        assertEquals(
            listOf("templates", "template_apply", "file_templates", "file_from_template"),
            listOf(TemplateTools.TEMPLATES, TemplateTools.TEMPLATE_APPLY, TemplateTools.FILE_TEMPLATES, TemplateTools.FILE_FROM_TEMPLATE).map { it.name },
        )
        assertEquals(listOf("injections", "inject_at", "docs"), listOf(LanguageTools.INJECTIONS, LanguageTools.INJECT_AT, LanguageTools.DOCS).map { it.name })
        assertEquals(
            listOf("bookmarks", "bookmark_add", "bookmark_remove", "project_view"),
            listOf(BookmarkTools.BOOKMARKS, BookmarkTools.BOOKMARK_ADD, BookmarkTools.BOOKMARK_REMOVE, BookmarkTools.PROJECT_VIEW).map { it.name },
        )
        assertEquals(
            listOf("psi_tree", "psi_at", "psi_replace", "psi_insert"),
            listOf(PsiTools.PSI_TREE, PsiTools.PSI_AT, PsiTools.PSI_REPLACE, PsiTools.PSI_INSERT).map { it.name },
        )
        assertEquals(listOf("index_keys", "index_query", "stub_query"), listOf(IndexTools.INDEX_KEYS, IndexTools.INDEX_QUERY, IndexTools.STUB_QUERY).map { it.name })
        assertEquals(listOf("uast_tree", "uast_at"), listOf(UastTools.UAST_TREE, UastTools.UAST_AT).map { it.name })
        assertEquals("workspace", WorkspaceTools.WORKSPACE.name)
        listOf(TemplateTools.TEMPLATE_APPLY, TemplateTools.FILE_FROM_TEMPLATE, LanguageTools.INJECT_AT, BookmarkTools.BOOKMARK_ADD, BookmarkTools.BOOKMARK_REMOVE, PsiTools.PSI_REPLACE, PsiTools.PSI_INSERT)
            .forEach { assertTrue(it.mutates, it.name) }
        listOf(TemplateTools.TEMPLATES, LanguageTools.INJECTIONS, LanguageTools.DOCS, BookmarkTools.BOOKMARKS, PsiTools.PSI_TREE, IndexTools.INDEX_KEYS, UastTools.UAST_TREE, WorkspaceTools.WORKSPACE)
            .forEach { assertFalse(it.mutates, it.name) }
    }

    @Test
    fun `the workspace entity types are listed in the parameter`() {
        val type = WorkspaceTools.WORKSPACE.params.first { it.name == "entity_type" }
        WorkspaceTools.ENTITY_TYPES.keys.forEach { assertTrue(it in type.description, "workspace.entity_type does not list $it") }
    }

    @Test
    fun `UAST rides on the Java plugin, declared optional with its config file, and only UastTools names it`() {
        val descriptor = File("src/main/resources/META-INF/plugin.xml").readText()
        val match = Regex("""<depends[^>]*>com\.intellij\.modules\.java</depends>""").find(descriptor)
        assertTrue(match != null && "optional=\"true\"" in match.value, "the Java module dependency must be optional")
        val configFile = Regex("""config-file="([^"]+)"""").find(match!!.value)?.groupValues?.get(1)
        assertTrue(configFile != null && File("src/main/resources/META-INF/$configFile").isFile, "config-file $configFile is missing")
        assertTrue(Regex("""bundledPlugin\(\s*"com\.intellij\.java"\s*\)""").containsMatchIn(File("build.gradle.kts").readText()))
        val users = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "org.jetbrains.uast" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals(listOf("UastTools.kt"), users)
    }
}
