package dev.lain.claudejb.controller.mcp

import dev.lain.claudejb.mcp.StdioBridge
import dev.lain.claudejb.model.session.launch.IdeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class SocketHomeTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the home is a private directory whose sockets fit the platform limit`() {
        val home = SocketHome.create(listOf(tmp))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.dir)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp.resolve(SocketHome.PARENT))))
        assertTrue(home.dir.startsWith(tmp.resolve(SocketHome.PARENT)))
        IdeServer.entries.forEach { assertTrue(home.socket(it).toString().length <= SocketHome.MAX_SOCKET_PATH) }
        home.remove()
        assertFalse(Files.exists(home.dir))
    }

    @Test
    fun `the token file is readable by its owner only and rewritten on rotation`() {
        val home = SocketHome.create(listOf(tmp))
        home.writeToken("first")
        home.writeToken("second")
        val file = home.dir.resolve(StdioBridge.TOKEN_FILE)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("second", Files.readString(file))
        home.remove()
    }

    @Test
    fun `a parent left open by an older build is closed to other users`() {
        val parent = Files.createDirectory(tmp.resolve(SocketHome.PARENT))
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"))
        val home = SocketHome.create(listOf(tmp))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(parent)))
        home.remove()
    }

    @Test
    fun `a base too long for a socket path is skipped for the next one`() {
        val deep = tmp.resolve("x".repeat(SocketHome.MAX_SOCKET_PATH))
        val home = SocketHome.create(listOf(deep, tmp))
        assertTrue(home.dir.startsWith(tmp.resolve(SocketHome.PARENT)))
        assertFalse(Files.exists(deep))
        home.remove()
    }

    @Test
    fun `no base short enough is an error, not a silent long path`() {
        val deep = tmp.resolve("x".repeat(SocketHome.MAX_SOCKET_PATH))
        assertThrows(IllegalStateException::class.java) { SocketHome.create(listOf(deep)) }
    }
}
