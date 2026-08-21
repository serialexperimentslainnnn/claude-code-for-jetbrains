package dev.lain.claudejb.vuln

object VulnDisclosure {

    const val ENDPOINT = "https://api.osv.dev/v1/querybatch"

    const val OPERATOR = "OSV.dev, run by the Open Source Security Foundation"

    val SENT: List<String> = listOf(
        "The name of every dependency this project resolves — direct and transitive alike.",
        "The exact version of each one, as your lockfiles pin it.",
        "The ecosystem each one belongs to: npm, PyPI, crates.io or Go.",
        "Nothing else. No file contents, no paths inside your project, no repository name, " +
            "no account, no credential, no identifier of any kind.",
    )

    val CAVEATS: List<String> = listOf(
        "The request is unauthenticated, so it carries no account — but it travels from your IP address.",
        "A complete dependency list with exact versions is close to a fingerprint of a private codebase.",
        "It also states, to whoever sees the request, which known-vulnerable versions you are running.",
        "Nothing is sent until you allow it here, and no scan ever starts on its own — not on open, " +
            "not on a build, not on a timer.",
    )
}
