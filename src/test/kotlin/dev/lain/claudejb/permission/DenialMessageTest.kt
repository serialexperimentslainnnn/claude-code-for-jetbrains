package dev.lain.claudejb.permission

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DenialMessageTest {

    private val instructions = listOf(
        "do not retry",
        "don't retry",
        "do not attempt",
        "another way",
        "do not try",
        "never run",
        "stop trying",
    )

    private val messages: List<String>
        get() = listOf(
            PermissionBroker.SENSITIVE_DENIED,
            PermissionBroker.denialMessage(null),
            PermissionBroker.denialMessage("reads credentials or key material outside the project: a key file"),
            PermissionBroker.denialMessage("runs with elevated privileges: sudo"),
        )

    @Test
    fun `a refusal tells the model why, and gives it no instruction about what to do next`() {
        messages.forEach { message ->
            instructions.forEach { order ->
                assertFalse(
                    message.contains(order, ignoreCase = true),
                    "a refusal that orders the model around is how one block became a session that refused " +
                        "to work at all: '$order' in \"$message\"",
                )
            }
        }
    }

    @Test
    fun `a refusal says the decision is about this call only`() {
        messages.forEach {
            assertTrue(
                it.contains("This applies to this call only"),
                "without this the model generalises from one block to every command: \"$it\"",
            )
        }
    }

    @Test
    fun `a refusal carries the reason it was given, verbatim`() {
        val reason = "runs an irreversible destructive operation: terraform destroy"

        assertTrue(PermissionBroker.denialMessage(reason).contains(reason))
    }

    @Test
    fun `with no reason to give it still says a guard refused, not that the call failed`() {
        listOf(PermissionBroker.SENSITIVE_DENIED, PermissionBroker.denialMessage(null)).forEach {
            assertTrue(it.contains("security guard"), it)
        }
    }

    @Test
    fun `it never names the switch that would turn the guard off`() {
        messages.forEach {
            assertFalse(
                it.contains("Settings", ignoreCase = true),
                "a block tells the model what it cannot do, never which lever to ask the user to move: \"$it\"",
            )
            assertFalse(it.contains("Permissive", ignoreCase = true), it)
        }
    }
}
