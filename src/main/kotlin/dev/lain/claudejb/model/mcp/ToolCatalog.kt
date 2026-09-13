package dev.lain.claudejb.model.mcp

class ToolCatalog(val domains: List<ToolDomain>) {
    private val byName: Map<String, Tool> = domains.flatMap { it.tools }.associateBy { it.spec.name }

    init {
        require(byName.size == domains.sumOf { it.tools.size }) { "tool names collide across domains" }
        require(domains.map { it.name }.toSet().size == domains.size) { "domain names collide" }
    }

    fun domain(name: String): ToolDomain? = domains.firstOrNull { it.name == name }

    fun tool(name: String): Tool? = byName[name]
}
