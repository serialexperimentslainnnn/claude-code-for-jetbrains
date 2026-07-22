package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure half of [RemoteMounts] — parsing `/proc/mounts` and deciding whether a path is remote against a fixed
 * snapshot. The impure half (reading `/proc`, `FileStore`) is exercised by the live suites; the decision logic,
 * which is what gates whether an agent may start, is unit-tested here.
 */
class RemoteMountsTest {

    private val procMounts = """
        proc /proc proc rw,nosuid 0 0
        /dev/sda1 / ext4 rw,relatime 0 0
        /dev/sda2 /home ext4 rw,relatime 0 0
        server:/export /net/nfs nfs4 rw,relatime 0 0
        //winbox/share /mnt/smb cifs rw 0 0
        user@host:/data /mnt/sshfs fuse.sshfs rw,nosuid 0 0
        tmpfs /run/user/1000 tmpfs rw 0 0
    """.trimIndent()

    @Test
    fun `parseMounts extracts mount points and fstypes`() {
        val mounts = RemoteMounts.parseMounts(procMounts)
        assertTrue(mounts.any { it.point == "/net/nfs" && it.type == "nfs4" })
        assertTrue(mounts.any { it.point == "/mnt/smb" && it.type == "cifs" })
        assertTrue(mounts.any { it.point == "/mnt/sshfs" && it.type == "fuse.sshfs" })
        assertTrue(mounts.any { it.point == "/" && it.type == "ext4" })
    }

    @Test
    fun `parseMounts un-escapes an octal space in a mount point`() {
        val one = RemoteMounts.parseMounts("""dev /mnt/My\040Drive vfat rw 0 0""").single()
        assertEquals("/mnt/My Drive", one.point)
    }

    @Test
    fun `a local project is not remote`() {
        val snap = RemoteMounts.Snapshot(remoteRoots = listOf("/net/nfs", "/mnt/smb"), isWsl = false)
        assertFalse(RemoteMounts.isRemote("/home/me/proj", snap))
    }

    @Test
    fun `a project on a network mount IS remote`() {
        val snap = RemoteMounts.Snapshot(remoteRoots = listOf("/net/nfs", "/mnt/smb"), isWsl = false)
        assertTrue(RemoteMounts.isRemote("/net/nfs/team/proj", snap))
        assertTrue(RemoteMounts.isRemote("/mnt/smb/shared/repo", snap))
    }

    @Test
    fun `a UNC project is remote regardless of the mount table`() {
        val snap = RemoteMounts.Snapshot(remoteRoots = emptyList(), isWsl = false)
        assertTrue(RemoteMounts.isRemote("""\\fileserver\projects\app""", snap))
    }

    @Test
    fun `under WSL a project on a foreign mount is remote, on mnt-c it is not`() {
        val snap = RemoteMounts.Snapshot(remoteRoots = emptyList(), isWsl = true)
        assertTrue(RemoteMounts.isRemote("/mnt/d/work/proj", snap))
        assertTrue(RemoteMounts.isRemote("/mnt/z/share/proj", snap))
        assertFalse(RemoteMounts.isRemote("/mnt/c/Users/me/proj", snap))
    }

    @Test
    fun `a blank path is not remote`() {
        val snap = RemoteMounts.Snapshot(remoteRoots = emptyList(), isWsl = false)
        assertFalse(RemoteMounts.isRemote(null, snap))
        assertFalse(RemoteMounts.isRemote("", snap))
    }

    /**
     * Regression: WSL2 surfaces /mnt/c over 9p, so its mount point used to land in remoteRoots and the startup
     * gate refused to launch on a perfectly normal C:\ project. /mnt/c is the sanctioned local disk and must be
     * exempt from the remote check even if it slipped into remoteRoots; every other WSL mount stays foreign.
     */
    @Test
    fun `on WSL mnt-c is never remote, even if its 9p mount slipped into remoteRoots`() {
        val snap = RemoteMounts.Snapshot(remoteRoots = listOf("/mnt/c", "/mnt/d"), isWsl = true)
        assertFalse(RemoteMounts.isRemote("/mnt/c", snap))
        assertFalse(RemoteMounts.isRemote("/mnt/c/dev/proj", snap))
        assertFalse(RemoteMounts.isRemote("/mnt/c/Users/me/code", snap))
        // Other Windows/network drives under WSL stay remote.
        assertTrue(RemoteMounts.isRemote("/mnt/d/work/proj", snap))
        assertTrue(RemoteMounts.isRemote("/mnt/z/share", snap))
    }

    @Test
    fun `on native Linux mnt-c is not special — governed only by real remote mounts`() {
        // Not WSL: /mnt/c is just a path; only a genuine remote mount root flags it.
        val plain = RemoteMounts.Snapshot(remoteRoots = emptyList(), isWsl = false)
        assertFalse(RemoteMounts.isRemote("/mnt/c/whatever", plain))
        val mounted = RemoteMounts.Snapshot(remoteRoots = listOf("/mnt/c"), isWsl = false)
        assertTrue(RemoteMounts.isRemote("/mnt/c/whatever", mounted))
    }
}
