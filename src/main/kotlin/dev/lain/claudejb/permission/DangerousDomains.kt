package dev.lain.claudejb.permission

/**
 * [SecurityRule.BLOCKED_DOMAIN] — **a curated set of anonymous drop/collect destinations**: paste sites,
 * one-shot file hosts, request-capture endpoints, out-of-band interaction services and public tunnels.
 *
 * ### What this is: a destination allow-list, inverted — and NOT threat intelligence
 * Every other rule here asks "what is this call touching"; this one asks "who is it talking to", and that is the
 * one question where a blacklist is the wrong shape by construction: an attacker owns a domain name and can buy
 * the next one. So the honest statement of what this buys is narrow, and it is made here rather than left to be
 * assumed:
 *  - it catches the **lazy** case, which is the common one — a model talked into exfiltrating something reaches
 *    for whatever paste site it has seen a thousand times in its training data, not for freshly registered
 *    infrastructure;
 *  - it names only services whose **entire purpose** is anonymous drop or collect, so a hit is meaningful on its
 *    own without any reputation feed behind it;
 *  - it is **not** an IOC feed and must never become one. `threat-intelligence-standards` is explicit that an
 *    indicator with no expiry is debt that generates false positives on its own, and that hashes and IPs sit at
 *    the bottom of the pyramid of pain precisely because they rotate. These are stable SERVICE names, which is a
 *    different kind of entry — closer to a TTP than to an indicator — and that is the only reason a list with no
 *    expiry date is defensible here.
 *
 * **Review cadence, and it is the price of having this at all**: read it once per release. An entry whose service
 * no longer exists costs nothing operationally and is deleted for honesty; a service that has clearly become the
 * default drop of the year is added. Do not wire this to a feed, and do not grow it with domains that merely
 * *host* bad things (a CDN, a code forge, a cloud bucket host) — those are where ordinary work lives, and a rule
 * that refuses them is a rule that gets switched off within the hour.
 *
 * ### It is matched on the HOST, suffix-wise, and never on the URL
 * `x.ngrok.io` matches `ngrok.io`; `notpastebin.com.example.org` does **not** match `pastebin.com`. A substring
 * test would accept both, which is the whole failure mode of naive domain matching.
 *
 * The reason string the guard builds carries **the host and nothing else**, deliberately: a URL's query string is
 * where a token or a signed link ends up, and that reason is shown in the transcript *and sent back to the model*.
 * Echoing a full URL there would make a security refusal into a credential disclosure.
 *
 * ### Where the candidates come from
 * [ToolInputScanner.urlCandidates] — a URL-shaped argument, or a URL inside a command line. A URL inside a
 * `CONTENT_KEY` payload is deliberately not offered: writing a link into a README is not egress.
 */
object DangerousDomains {

    /**
     * The built-in set. Registrable domains, lower-case, no scheme and no leading dot — [blockedHit] matches a
     * host against each one exactly or as a suffix.
     *
     * Grouped by what the service IS, because that grouping is the admission criterion: if a candidate entry does
     * not belong to one of these five groups, it does not belong here.
     */
    val BLOCKED_DOMAINS: Set<String> = setOf(
        // Paste sites — text in, public URL out, no account.
        "pastebin.com", "paste.ee", "hastebin.com", "ix.io",
        // One-shot file hosts. `anonfiles.com` shut down in August 2023 under exactly the abuse this rule is
        // about, and it is listed anyway: an entry that never matches costs one string comparison and nothing
        // else, while a name whose brand is still being reused is the wrong thing to be clever about. **Removing
        // an entry because it looks harmless is a narrowing of detection, and narrowing detection is not this
        // list's business** — the review cadence above is for ADDING. The copycats on other TLDs are not covered
        // by this entry and never were; that is an argument for more entries, never for one fewer.
        "transfer.sh", "file.io", "anonfiles.com", "gofile.io", "0x0.st",
        // Request capture — a URL that records whatever is sent to it, which is exfiltration with a UI.
        "webhook.site", "requestbin.com", "beeceptor.com", "pipedream.net",
        // Out-of-band interaction / canary services: they exist to prove data left a network.
        "interact.sh", "oastify.com", "burpcollaborator.net", "canarytokens.com",
        // Public tunnels — a local port published on the internet under someone else's name.
        "ngrok.io", "ngrok-free.app",
    )

    /**
     * The first blocked HOST among [urls], or null. [extra] is the user's own list, **added** to the built-in set
     * and never able to shrink it — the same additive-only rule [CredentialPaths] follows for its globs.
     */
    internal fun blockedHit(urls: List<String>, extra: List<String>): String? {
        if (urls.isEmpty()) return null
        val domains = BLOCKED_DOMAINS + extra.mapNotNull(::normalizeDomain)
        return urls.asSequence().mapNotNull(::host).firstOrNull { h -> domains.any { matches(h, it) } }
    }

    /**
     * The host of [url], lower-cased, without userinfo, port or trailing dot — or null when there is none.
     *
     * Hand-parsed rather than through `java.net.URI`, which **throws** on the malformed input this is most likely
     * to be handed (a URL glued to a shell operator, a template placeholder inside the path) and is documented to
     * return null for the host of an opaque URI. A thrown exception here would escape `classify` and leave the
     * `can_use_tool` request unanswered, which is this package's worst failure mode: silent, and it looks like the
     * model stopped rather than like a plugin defect.
     */
    fun host(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostAndPort = authority.substringAfterLast('@') // drop `user:pass@`
        val bare = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringAfter('[').substringBefore(']') // IPv6 literal
        } else {
            hostAndPort.substringBefore(':')
        }
        return bare.trim().trimEnd('.').lowercase().ifBlank { null }
    }

    /** Exact, or a subdomain of it. Never a substring: see the class doc. */
    private fun matches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /**
     * A user-supplied entry as this rule stores one: `https://Pastebin.com/`, `*.pastebin.com` and
     * `.PASTEBIN.com` all mean `pastebin.com`.
     *
     * Forgiving on purpose — the field is free text in a settings page, and an entry silently ignored for a
     * leading dot is a rule the user believes is on and is not.
     */
    private fun normalizeDomain(raw: String): String? = host(raw.trim())
        ?: raw.trim().removePrefix("*.").removePrefix(".").trimEnd('/').trimEnd('.').lowercase().ifBlank { null }
}
