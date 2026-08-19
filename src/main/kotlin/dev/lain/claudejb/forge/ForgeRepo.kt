package dev.lain.claudejb.forge

enum class ForgeProvider { GITHUB, GITLAB }

data class ForgeRepo(
    val provider: ForgeProvider,
    val host: String,
    val owner: String,
    val name: String,
) {

    val path: String get() = "$owner/$name"
}
