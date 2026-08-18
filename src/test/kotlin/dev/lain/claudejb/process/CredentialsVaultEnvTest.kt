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

/**
 * The two pure halves of [CredentialsVault]: what a vaulted blob becomes in the child process's environment
 * ([CredentialsVault.overlayFrom]), and what the non-interactive renewal runs under
 * ([CredentialsVault.renewalEnv]).
 *
 * `CredentialsVaultHeadlessTest` covers the *decisions* around them — expiry, renewability, and an explicit
 * Settings credential outranking the vaulted one — by driving the real PasswordSafe. What it never asserted is
 * the mapping itself: every one of its `envOverlay` assertions uses a blob carrying nothing but an access
 * token, so the fields added when "only the access token is handed over" was found to blind `get_usage`
 * (the scopes above all) were reachable only through code nothing exercised.
 *
 * That is the gap this closes, and it matters more than its size: the map [CredentialsVault.overlayFrom]
 * returns IS the authenticated identity of every session the plugin runs.
 */
class CredentialsVaultEnvTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    /** The shape `claude auth login` writes into `~/.claude/.credentials.json`, whole. */
    private val fullOauth = obj(
        """
        {"accessToken":"at-live","expiresAt":9999999999999,"refreshToken":"rt-live",
         "refreshTokenExpiresAt":9999999999999,"scopes":["user:inference","user:profile"],
         "subscriptionType":"max","rateLimitTier":"default_claude_max_20x"}
        """.trimIndent(),
    )

    /** The `oauthAccount` object the same login writes into `~/.claude.json`. */
    private val account = obj(
        """
        {"accountUuid":"acc-1","organizationUuid":"org-1","emailAddress":"dev@example.com",
         "organizationName":"Dev's Organization"}
        """.trimIndent(),
    )

    // ── overlayFrom: the whole blob reaches the binary, field by field ────────────────────────────────────

    @Test
    fun `the whole credential travels in the environment, under the names the binary reads`() {
        val env = CredentialsVault.overlayFrom("at-live", fullOauth, account)

        assertEquals(
            mapOf(
                "CLAUDE_CODE_OAUTH_TOKEN" to "at-live",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN" to "rt-live",
                // Space-separated: the OAuth `scope` encoding, not a JSON array and not comma-separated.
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
        // `SDKControlGetUsageResponse.rate_limits_available` is documented false on a "missing profile scope",
        // and handing over only the access token is what once made `get_usage` answer `rate_limits: null`.
        val scopes = CredentialsVault.overlayFrom("at-live", fullOauth, null)["CLAUDE_CODE_OAUTH_SCOPES"]
        assertTrue(scopes != null && "user:profile" in scopes.split(" "), "scopes: $scopes")
    }

    @Test
    fun `absent fields are omitted, never blanked`() {
        // An empty env var is a value. A blank scope list or subscription would be the plugin telling the
        // binary something false about the account.
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
        // CREDENTIALS_JSON is file-shaped and deliberately not an env var: putting a bearer blob somewhere
        // nothing reads it from is cost with no benefit.
        assertNull(env["CLAUDE_CREDENTIALS_JSON"])
        // And the host must not announce that IT refreshes the token — only the binary can spend one.
        assertNull(env["CLAUDE_CODE_SDK_HAS_OAUTH_REFRESH"])
    }

    @Test
    fun `the account is optional and its absence costs only the account fields`() {
        val env = CredentialsVault.overlayFrom("at-live", fullOauth, null)
        assertEquals("rt-live", env["CLAUDE_CODE_OAUTH_REFRESH_TOKEN"])
        assertNull(env["CLAUDE_CODE_USER_EMAIL"])
    }

    // ── refreshEnv: the planted file is the ONLY source the refresh may use ───────────────────────────────

    @Test
    fun `the refresh environment carries no credential at all, so the file is the only source`() {
        // The renewal used to put the refresh token and its scopes in the environment. That route shipped for a
        // release and never renewed anything; what works is planting the file (`renewOnDisk`). Leaving any
        // `CLAUDE_CODE_OAUTH_*` in place would make the outcome depend on which source the binary prefers —
        // and the access token in particular is the one being replaced, so authenticating with it means
        // refreshing nothing at all.
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
        // A hand-written `Claude_Code_Oauth_Token` in Settings is the SAME variable on Windows. An exact-match
        // removal left it in place, and it was the very expired token the refresh exists to replace.
        listOf("Claude_Code_Oauth_Token", "claude_code_oauth_refresh_token", "CLAUDE_code_OAUTH_SCOPES").forEach { name ->
            val env = CredentialsVault.refreshEnv(mapOf(name to "x"))
            assertTrue(env.isEmpty(), "$name survived the strip")
        }
    }

    // ── env, never argv ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no credential is ever built into a command line in the process package`() {
        // argv lands in /proc/<pid>/cmdline, which every process running as the user can read — the exact
        // exposure the vault exists to remove from the disk. Pinned at the source because it is a rule about
        // how a call is written, and the call that breaks it would look perfectly ordinary in review.
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

        // The positive half: the one place that runs the binary for auth must still pass the env.
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
        /** Names that only ever appear where a credential does. */
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
