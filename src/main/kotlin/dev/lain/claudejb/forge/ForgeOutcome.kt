package dev.lain.claudejb.forge

sealed interface ForgeOutcome {

    data object Done : ForgeOutcome

    data class Refused(val reason: ForgeRefusal) : ForgeOutcome
}

enum class ForgeRefusal(val note: String) {

    NO_TOKEN("There is no token for this host, so nothing was sent."),

    NO_PERMISSION("Your account does not have the rights for this on this project."),

    TOKEN_TOO_NARROW("The token was accepted but does not carry the permission this needs."),

    NOT_MERGEABLE("The forge will not merge this yet: it is a draft, closed, or its checks have not passed."),

    CONFLICTED("The branches conflict, so the forge refused to merge them."),

    STALE("The branch moved since this view read it. Refresh and look again before deciding."),

    ALREADY_FINISHED("That run had already finished, so there was nothing to act on."),

    SELF_APPROVAL("This forge does not let the author approve their own request."),

    RATE_LIMITED("The forge is rate-limiting this token. It will work again shortly."),

    UNREACHABLE("The forge could not be reached, so nothing was sent."),

    REFUSED("The forge refused the request and did not say why in a way this build understands."),

    ON_EDT("The action was asked for on the UI thread and refused."),
}
