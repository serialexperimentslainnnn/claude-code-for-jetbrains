package dev.lain.claudejb.model.permission.vocab

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityRuleTextsTest {

    @Test
    fun `every rule carries a hint, a refusal and a reason`() {
        SecurityRule.entries.forEach { rule ->
            assertTrue(rule.hint.isNotBlank(), rule.name)
            assertTrue(rule.blockedReason.isNotBlank(), rule.name)
            assertTrue(rule.blockedWhy.isNotBlank(), rule.name)
        }
    }

    @Test
    fun `a refusal is addressed to the model and a reason explains the rule`() {
        SecurityRule.entries.forEach { rule ->
            assertTrue(rule.blockedReason.startsWith("You can't") || rule.blockedReason.startsWith("Analyse"), rule.name)
            assertTrue(rule.blockedWhy.endsWith("."), rule.name)
        }
    }
}
