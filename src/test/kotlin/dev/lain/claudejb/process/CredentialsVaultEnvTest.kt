package dev.lain.claudejb.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CredentialsVaultEnvTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private val fullOauth = obj(
        """
        {"accessToken":"at-live","expiresAt":9999999999999,"refreshToken":"rt-live",
         "refreshTokenExpiresAt":9999999999999,"scopes":["user:inference","user:profile"],
         "subscriptionType":"max","rateLimitTier":"default_claude_max_20x"}
        """.trimIndent(),
    )

    private val account = obj(
        """
        {"accountUuid":"acc-1","organizationUuid":"org-1","emailAddress":"dev@example.com",
         "organizationName":"Dev's Organization"}
        """.trimIndent(),
    )

    @Test
    fun `the whole credential travels in the environment, under the names the binary reads`() {
        val env = CredentialsVault.overlayFrom("at-live", fullOauth, account)

        assertEquals(
            mapOf(
                "CLAUDE_CODE_OAUTH_TOKEN" to "at-live",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN" to "rt-live",
                "CLAUDE_CODE_OAUTH_SCOPES" to "user:inference user:profile",
                "CLAUDE_CODE_SUBSCRIPTION_TYPE" to "max",
                "CLAUDE_CODE_RATE_LIMIT_TIER" to "default_claude_max_20x",
                "CLAUDE_CODE_ACCOUNT_UUID" to "acc-1",
                "CLAUDE_CODE_ORGANIZATION_UUID" to "org-1",
                "CLAUDE_CODE_USER_EMAIL" to "dev@example.com",
            ),
            env,
        )
    }

    @Test
    fun `the profile scope is carried, because without it every plan meter goes dark`() {
        val scopes = CredentialsVault.overlayFrom("at-live", fullOauth, null)["CLAUDE_CODE_OAUTH_SCOPES"]
        assertTrue(scopes != null && "user:profile" in scopes.split(" "), "scopes: $scopes")
    }

    @Test
    fun `absent fields are omitted, never blanked`() {
        val minimal = obj("""{"accessToken":"at","expiresAt":9999999999999}""")
        val env = CredentialsVault.overlayFrom("at", minimal, null)

        assertEquals(mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "at"), env)
        assertFalse(env.values.any { it.isBlank() })
    }

    @Test
    fun `a blank or empty field is treated as absent rather than sent as an empty string`() {
        val blanks = obj(
            """
            {"accessToken":"at","expiresAt":9999999999999,"refreshToken":"  ","scopes":[],
             "subscriptionType":"","rateLimitTier":null}
            """.trimIndent(),
        )
        val env = CredentialsVault.overlayFrom("at", blanks, obj("""{"accountUuid":"","emailAddress":" "}"""))

        assertEquals(mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "at"), env)
    }

    @Test
    fun `a non-string entry in scopes is dropped, not stringified`() {
        val odd = obj("""{"accessToken":"at","expiresAt":9999999999999,"scopes":["user:profile","",null]}""")
        assertEquals("user:profile", CredentialsVault.overlayFrom("at", odd, null)["CLAUDE_CODE_OAUTH_SCOPES"])
    }

    @Test
    fun `neither the raw blob nor a refresh claim we cannot honour is offered to the child`() {
        val env = CredentialsVault.overlayFrom("at-live", fullOauth, account)
        assertNull(env["CLAUDE_CREDENTIALS_JSON"])
        assertNull(env["CLAUDE_CODE_SDK_HAS_OAUTH_REFRESH"])
    }

    @Test
    fun `the account is optional and its absence costs only the account fields`() {
        val env = CredentialsVault.overlayFrom("at-live", fullOauth, null)
        assertEquals("rt-live", env["CLAUDE_CODE_OAUTH_REFRESH_TOKEN"])
        assertNull(env["CLAUDE_CODE_USER_EMAIL"])
    }

    @Test
    fun `the refresh environment carries no credential at all, so the file is the only source`() {
        val env = CredentialsVault.refreshEnv(
            mapOf(
                "CLAUDE_CODE_OAUTH_TOKEN" to "revoked",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN" to "stale",
                "CLAUDE_CODE_OAUTH_SCOPES" to "wrong",
                "HTTPS_PROXY" to "http://proxy:3128",
            ),
        )

        assertFalse(
            env.keys.any { it.startsWith("CLAUDE_CODE_OAUTH", ignoreCase = true) },
            "the refresh must not be able to authenticate from the environment",
        )
        assertEquals("http://proxy:3128", env["HTTPS_PROXY"], "the rest of the user's env must survive")
    }

    @Test
    fun `the strip is CASE-INSENSITIVE, because Windows env names are`() {
        listOf("Claude_Code_Oauth_Token", "claude_code_oauth_refresh_token", "CLAUDE_code_OAUTH_SCOPES").forEach { name ->
            val env = CredentialsVault.refreshEnv(mapOf(name to "x"))
            assertTrue(env.isEmpty(), "$name survived the strip")
        }
    }

    @Test
    fun `no credential is ever built into a command line in the process package`() {
        val offenders = processSources().flatMap { file ->
            file.readLines().withIndex().mapNotNull { (i, raw) ->
                val line = raw.trim()
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@mapNotNull null
                val buildsArgv = "GeneralCommandLine(" in line || "listOf(binary" in line
                val namesCredential = CREDENTIAL_TOKENS.any { it in line }
                if (buildsArgv && namesCredential) "${file.name}:${i + 1}: $line" else null
            }
        }
        assertTrue(offenders.isEmpty()) { "a credential reaches the process as an ARGUMENT:\n$offenders" }

        val authCli = processSources().first { it.name == "AuthCli.kt" }.readText()
        assertTrue("withEnvironment(env)" in authCli, "AuthCli must pass the credential through the ENVIRONMENT")
    }

    private fun processSources(): List<File> =
        sequenceOf(
            File("src/main/kotlin/dev/lain/claudejb/process"),
            File("../src/main/kotlin/dev/lain/claudejb/process"),
        ).first { it.isDirectory }
            .listFiles { f: File -> f.isFile && f.extension == "kt" }
            .orEmpty()
            .toList()

    private companion object {
        val CREDENTIAL_TOKENS = listOf(
            "OAUTH_TOKEN",
            "API_KEY",
            "accessToken",
            "refreshToken",
            "CLAUDE_CODE_OAUTH",
            "sk-ant-",
        )
    }
}
