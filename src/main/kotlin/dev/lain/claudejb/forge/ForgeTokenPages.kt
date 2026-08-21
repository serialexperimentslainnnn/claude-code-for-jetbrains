package dev.lain.claudejb.forge

import dev.lain.claudejb.git.GitRemoteProvider
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ForgeTokenReach(val label: String) {
    READ("Read only"),
    WRITE("Read and write"),
}

data class ForgeTokenPage(val label: String, val url: String, val note: String)

object ForgeTokenPages {

    const val TOKEN_NAME = "Claude Code Native"

    fun of(provider: GitRemoteProvider, host: String, reach: ForgeTokenReach): List<ForgeTokenPage> =
        when (provider) {
            GitRemoteProvider.GITLAB -> listOf(gitlab(host, reach))
            GitRemoteProvider.GITHUB -> listOf(githubClassic(host, reach), githubFineGrained(host, reach))
            GitRemoteProvider.OTHER -> emptyList()
        }

    private fun gitlab(host: String, reach: ForgeTokenReach): ForgeTokenPage {
        val scopes = if (reach == ForgeTokenReach.READ) "read_api" else "api"
        val note = if (reach == ForgeTokenReach.READ) {
            "Creates a token with the <code>read_api</code> scope, which is all the reading needs."
        } else {
            "GitLab has no narrower write scope than <code>api</code>, so this one can do anything your " +
                "account can. Pick read only unless you want the actions."
        }
        return ForgeTokenPage(
            label = "Create a ${reach.label.lowercase()} token",
            url = "https://$host/-/user_settings/personal_access_tokens" +
                "?name=${encode(TOKEN_NAME)}&scopes=$scopes",
            note = note,
        )
    }

    private fun githubClassic(host: String, reach: ForgeTokenReach): ForgeTokenPage {
        val scopes = if (reach == ForgeTokenReach.READ) "repo:status,public_repo" else "repo,workflow"
        return ForgeTokenPage(
            label = "Create a classic ${reach.label.lowercase()} token",
            url = "https://${webHost(host)}/settings/tokens/new" +
                "?description=${encode(TOKEN_NAME)}&scopes=$scopes",
            note = "The scopes arrive already ticked, and a classic token is wider than this needs.",
        )
    }

    private fun githubFineGrained(host: String, reach: ForgeTokenReach): ForgeTokenPage {
        val wanted = if (reach == ForgeTokenReach.READ) {
            "Pull requests: read, Actions: read"
        } else {
            "Pull requests: read and write, Actions: read and write, Contents: read and write"
        }
        val caveat = if (reach == ForgeTokenReach.WRITE) {
            " Merging needs Contents write, which also lets the token push."
        } else {
            ""
        }
        return ForgeTokenPage(
            label = "Create a fine-grained ${reach.label.lowercase()} token",
            url = "https://${webHost(host)}/settings/personal-access-tokens/new",
            note = "This page cannot be pre-filled, so tick these yourself: $wanted.$caveat",
        )
    }

    private fun webHost(host: String): String = if (host.equals(DOT_COM, ignoreCase = true)) DOT_COM else host

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private const val DOT_COM = "github.com"
}
