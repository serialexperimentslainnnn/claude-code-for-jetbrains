package dev.lain.claudejb.session

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object RemoteMounts {

    private val REMOTE_FS_TYPES = setOf(
        "nfs", "nfs4", "cifs", "smb", "smb3", "smbfs", "afpfs", "ncpfs", "afs", "9p",
        "fuse.sshfs", "sshfs", "fuse.rclone", "fuse.s3fs", "fuse.gcsfuse", "davfs", "davfs2",
        "glusterfs", "ceph", "fuse.glusterfs", "lustre", "gpfs", "beegfs", "vboxsf", "prl_fs",
    )

    data class Mount(val point: String, val type: String)

    data class Snapshot(
        val remoteRoots: List<String>,
        val isWsl: Boolean,
    )

    @Volatile
    private var cached: Snapshot? = null

    fun snapshot(): Snapshot = cached ?: synchronized(this) {
        cached ?: detect().also { cached = it }
    }

    fun isRemote(path: String?, snap: Snapshot = snapshot()): Boolean {
        if (path.isNullOrBlank()) return false
        val p = path.replace('\\', '/')
        if (snap.isWsl && p.startsWith("/mnt/")) return !(p == "/mnt/c" || p.startsWith("/mnt/c/"))
        if (SensitiveGuardUnc.isUnc(p)) return true
        if (snap.remoteRoots.any { under(p, it) }) return true
        return runCatching {
            val store = Files.getFileStore(Paths.get(path))
            store.type()?.lowercase() in REMOTE_FS_TYPES || store.name().startsWith("//") || store.name().startsWith("\\\\")
        }.getOrDefault(false)
    }

    private fun detect(): Snapshot {
        val wsl = detectWsl()
        val mountsFile = listOf("/proc/self/mounts", "/proc/mounts").map(::File).firstOrNull { it.canRead() }
        val content = mountsFile?.let { runCatching { it.readText() }.getOrNull() }
        val remoteRoots = content
            ?.let { parseMounts(it).filter { m -> m.type.lowercase() in REMOTE_FS_TYPES }.map { m -> m.point } }
            .orEmpty()
            .filterNot { wsl && it.startsWith("/mnt/") }
        return Snapshot(remoteRoots = remoteRoots, isWsl = wsl)
    }

    fun parseMounts(content: String): List<Mount> =
        content.lineSequence()
            .mapNotNull { line ->
                val f = line.split(' ')
                if (f.size >= 3 && f[1].isNotBlank()) Mount(f[1].replace("\\040", " "), f[2]) else null
            }
            .toList()

    private fun detectWsl(): Boolean {
        if (System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null) return true
        for (p in listOf("/proc/version", "/proc/sys/kernel/osrelease")) {
            val text = runCatching { File(p).readText() }.getOrNull() ?: continue
            if (text.contains("microsoft", ignoreCase = true) || text.contains("WSL", ignoreCase = true)) return true
        }
        return false
    }

    private fun under(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        return r.isNotEmpty() && r != "/" && (path == r || path.startsWith("$r/", ignoreCase = true))
    }
}

private object SensitiveGuardUnc {
    fun isUnc(path: String): Boolean = dev.lain.claudejb.permission.ForeignTerritory.isUnc(path)
}
