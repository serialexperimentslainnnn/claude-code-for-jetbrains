package dev.lain.claudejb.vuln

interface ScanListener {

    fun progress(done: Int, total: Int)

    fun cancelled(): Boolean
}

sealed interface ScanAnswer {

    data class Known(val report: VulnReport) : ScanAnswer

    data class Silent(val reason: ScanSilence) : ScanAnswer
}

enum class ScanSilence(val wire: String, val note: String) {

    NO_SCANNER(
        "noScanner",
        "This build carries no vulnerability-database client yet, so nothing was sent anywhere.",
    ),

    NO_CONSENT(
        "noConsent",
        "Nothing was sent: this project has not allowed the scan.",
    ),

    NOTHING_TO_SCAN(
        "nothingToScan",
        "No dependency manifest this build can read was found in this project, so there was nothing to ask about.",
    ),

    ON_EDT(
        "onEdt",
        "The scan was asked for on the UI thread and refused.",
    ),

    CANCELLED(
        "cancelled",
        "The scan was cancelled. Whatever had already been sent cannot be recalled.",
    ),

    UNREACHABLE(
        "unreachable",
        "The vulnerability database could not be reached.",
    ),

    REFUSED(
        "refused",
        "The vulnerability database refused the request.",
    ),

    OVERSIZED(
        "oversized",
        "The vulnerability database answered with more than this build will read.",
    ),

    MALFORMED(
        "malformed",
        "The vulnerability database answered with something this build could not read.",
    ),
}

interface VulnScanner {

    val endpoint: String

    fun scan(inventory: List<VulnComponent>, listener: ScanListener): ScanAnswer
}
