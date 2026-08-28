package dev.lain.claudejb.vuln

enum class VulnConsent(val wire: String) {
    UNASKED("unasked"),
    GRANTED("granted"),
    WITHDRAWN("withdrawn"),
    ;

    companion object {

        fun from(wire: String?): VulnConsent = entries.firstOrNull { it.wire == wire } ?: UNASKED
    }
}
