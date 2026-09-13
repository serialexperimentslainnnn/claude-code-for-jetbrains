package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.execution.services.ServiceViewProvidingContributor
import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.mcp.ToolException

internal class ServiceNode(
    val path: String,
    val name: String,
    val state: String,
    val value: Any,
    val root: ServiceViewContributor<*>,
    val descriptor: ServiceViewDescriptor,
) {
    val kind: String get() = root.javaClass.simpleName

    fun matches(filter: String): Boolean =
        path.contains(filter, ignoreCase = true) || state.contains(filter, ignoreCase = true) || kind.contains(filter, ignoreCase = true)
}

internal class ServiceWalk(val nodes: List<ServiceNode>, val truncated: Boolean)

internal class ServiceTree(private val project: Project) {

    fun walk(max: Int): ServiceWalk {
        val walker = Walker(max, wanted = null)
        walker.roots()
        return ServiceWalk(walker.nodes.take(max), walker.nodes.size > max)
    }

    fun find(path: String): ServiceNode {
        val walker = Walker(LOOKUP_CEILING, wanted = path)
        walker.roots()
        return walker.nodes.firstOrNull { it.path == path }
            ?: throw ToolException("no service at $path; services() lists the paths, and a node's name may have changed since")
    }

    private inner class Walker(private val max: Int, private val wanted: String?) {

        val nodes = ArrayList<ServiceNode>()
        private val taken = HashSet<String>()

        private fun done(): Boolean = nodes.size > max || (wanted != null && nodes.lastOrNull()?.path == wanted)

        fun roots() {
            for (root in ServiceViewContributor.CONTRIBUTOR_EP_NAME.extensionList) {
                if (done()) return
                val services = servicesOf(root)
                if (services.isEmpty()) continue
                val descriptor = runCatching { root.getViewDescriptor(project) }.getOrNull() ?: continue
                val node = add("", descriptor, root, root)
                descend(root, root, services, node.path, 1)
            }
        }

        private fun descend(
            contributor: ServiceViewContributor<*>,
            root: ServiceViewContributor<*>,
            services: List<Any>,
            parentPath: String,
            depth: Int,
        ) {
            if (depth > MAX_DEPTH) return
            for (service in services) {
                if (done()) return
                val descriptor = runCatching { descriptorOf(contributor, service) }.getOrNull() ?: continue
                val value = if (service is ServiceViewProvidingContributor<*, *>) service.asService() else service
                val node = add(parentPath, descriptor, value, root)
                if (service is ServiceViewContributor<*>) descend(service, root, servicesOf(service), node.path, depth + 1)
            }
        }

        private fun servicesOf(contributor: ServiceViewContributor<*>): List<Any> =
            runCatching { contributor.getServices(project) }.getOrDefault(emptyList()).filterNotNull()

        private fun add(parentPath: String, descriptor: ServiceViewDescriptor, value: Any, root: ServiceViewContributor<*>): ServiceNode {
            val presentation = descriptor.presentation
            val name = presentation.presentableText.orEmpty()
            val path = uniquePath(parentPath, name, taken)
            taken += path
            val node = ServiceNode(path, name, presentation.locationString.orEmpty(), value, root, descriptor)
            nodes += node
            return node
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun descriptorOf(contributor: ServiceViewContributor<*>, service: Any): ServiceViewDescriptor =
        (contributor as ServiceViewContributor<Any>).getServiceDescriptor(project, service)

    companion object {

        private const val MAX_DEPTH = 12
        private const val LOOKUP_CEILING = 5_000
        const val UNNAMED = "unnamed"

        fun uniquePath(parentPath: String, name: String, taken: Set<String>): String {
            val segment = name.ifBlank { UNNAMED }
            val base = if (parentPath.isEmpty()) segment else "$parentPath/$segment"
            var candidate = base
            var ordinal = 2
            while (candidate in taken) candidate = "$base (${ordinal++})"
            return candidate
        }
    }
}
