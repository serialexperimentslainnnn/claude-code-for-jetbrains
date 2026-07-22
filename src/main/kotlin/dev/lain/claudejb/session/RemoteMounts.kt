package dev.lain.claudejb.session

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Answers one question that turns out to be load-bearing: **is this path on a network / removable / foreign
 * filesystem?**
 *
 * Why it matters enough to have its own file: a shared drive is where an organisation's other people's work lives.
 * Point an autonomous agent — shell access, an IDE's reach, and programming/offensive skills — at a project that
 * *sits on* such a share and you have built a lateral-movement launchpad, not a coding assistant. So the plugin
 * (a) **refuses to start** a session whose project root is on a remote mount ([isRemote], gated in
 * `ClaudeSession.start`), and (b) feeds the per-tool guard the set of mounts to treat as foreign
 * ([SensitiveGuard.Policy.guardedRoots] / `blockForeignWslMounts`).
 *
 * Detection is best-effort and layered, because no single signal is portable:
 *  - `/proc/self/mountinfo` / `/proc/mounts` on Linux → the authoritative fstype per mount point (the parsing is
 *    pure — [parseMounts] — so it is unit-tested);
 *  - `java.nio.file.FileStore.type()` as a cross-platform fallback (macOS, Windows mapped drives);
 *  - **UNC** (`\\server\share`) is remote by construction, on any OS;
 *  - under **WSL**, everything on `/mnt/` other than `/mnt/c` is treated as foreign (a second Windows disk, or a
 *    network drive surfaced by the host) — bluntly, because `/mnt/c` and a mapped `/mnt/z` are the same `drvfs`
 *    fstype and cannot be told apart by type alone.
 *
 * The snapshot is computed once per process ([snapshot]); mounts do not meaningfully change within a session.
 */
object RemoteMounts {

    /** Filesystem types that are network / remote / userspace-remote — never a place to run an agent rooted. */
    private val REMOTE_FS_TYPES = setOf(
        "nfs", "nfs4", "cifs", "smb", "smb3", "smbfs", "afpfs", "ncpfs", "afs", "9p",
        "fuse.sshfs", "sshfs", "fuse.rclone", "fuse.s3fs", "fuse.gcsfuse", "davfs", "davfs2",
        "glusterfs", "ceph", "fuse.glusterfs", "lustre", "gpfs", "beegfs", "vboxsf", "prl_fs",
    )

    /** A parsed mount point + its fstype. */
    data class Mount(val point: String, val type: String)

    /** What the process learned about this host's mounts. */
    data class Snapshot(
        /** Mount points whose fstype is remote — treat everything under them as foreign. */
        val remoteRoots: List<String>,
        /** Running under WSL: `/mnt/<x != c>` is foreign. */
        val isWsl: Boolean,
    )

    @Volatile
    private var cached: Snapshot? = null

    /** The host snapshot, computed once. Cheap, but there is no reason to re-read `/proc` every launch. */
    fun snapshot(): Snapshot = cached ?: synchronized(this) {
        cached ?: detect().also { cached = it }
    }

    /**
     * True when [path] lives on a network / removable / foreign filesystem — the check `ClaudeSession.start` gates
     * on, and the reason a project there is refused. Null/blank → false (nothing to protect).
     */
    fun isRemote(path: String?, snap: Snapshot = snapshot()): Boolean {
        if (path.isNullOrBlank()) return false
        val p = path.replace('\\', '/')
        // WSL: /mnt/c is the local Windows disk — the sanctioned drive, never remote. Every other /mnt/* is a
        // foreign/second/network Windows drive. This is decided BEFORE the fstype checks below, because WSL2
        // surfaces /mnt/c over 9p/drvfs and it would otherwise be flagged as a network mount (the reported bug).
        if (snap.isWsl && p.startsWith("/mnt/")) return !(p == "/mnt/c" || p.startsWith("/mnt/c/"))
        if (SensitiveGuardUnc.isUnc(p)) return true
        if (snap.remoteRoots.any { under(p, it) }) return true
        // Cross-platform fallback: ask the JVM what filesystem backs the path (catches macOS/Windows shares that
        // never appear in /proc). Best-effort — an unreadable path just isn't flagged here.
        return runCatching {
            val store = Files.getFileStore(Paths.get(path))
            store.type()?.lowercase() in REMOTE_FS_TYPES || store.name().startsWith("//") || store.name().startsWith("\\\\")
        }.getOrDefault(false)
    }

    /** The guard policy inputs derived from the host: the mount roots to treat as foreign, and the WSL flag. */
    fun guardedRoots(snap: Snapshot = snapshot()): List<String> = snap.remoteRoots
    fun blockForeignWslMounts(snap: Snapshot = snapshot()): Boolean = snap.isWsl

    // ── detection ────────────────────────────────────────────────────────────────────────────────────────

    private fun detect(): Snapshot {
        val wsl = detectWsl()
        val mountsFile = listOf("/proc/self/mounts", "/proc/mounts").map(::File).firstOrNull { it.canRead() }
        val content = mountsFile?.let { runCatching { it.readText() }.getOrNull() }
        val remoteRoots = content
            ?.let { parseMounts(it).filter { m -> m.type.lowercase() in REMOTE_FS_TYPES }.map { m -> m.point } }
            .orEmpty()
            // Under WSL, every /mnt/* mount is a Windows drive surfaced over 9p/drvfs — including the local C:.
            // Those are governed by the dedicated /mnt/c rule (isRemote / blockForeignWslMounts), so they must NOT
            // be treated as generic network mounts here, or /mnt/c (the sanctioned local disk) is flagged remote
            // and the plugin refuses to start on a perfectly normal WSL project.
            .filterNot { wsl && it.startsWith("/mnt/") }
        return Snapshot(remoteRoots = remoteRoots, isWsl = wsl)
    }

    /** PURE: `/proc/mounts` text → the mount points and their fstypes. Unit-tested. */
    fun parseMounts(content: String): List<Mount> =
        content.lineSequence()
            .mapNotNull { line ->
                val f = line.split(' ')
                // `<device> <mountpoint> <fstype> <opts> …` — octal-escaped spaces in the mountpoint stay as-is.
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

/** Tiny shim so [RemoteMounts] can reuse the guard's UNC test without a circular import at the call site. */
private object SensitiveGuardUnc {
    fun isUnc(path: String): Boolean = dev.lain.claudejb.permission.SensitiveGuard.isUnc(path)
}
