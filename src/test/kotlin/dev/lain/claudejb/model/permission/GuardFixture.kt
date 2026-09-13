package dev.lain.claudejb.model.permission

import dev.lain.claudejb.model.permission.paths.CredentialPaths
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object GuardFixture {

    const val HOME = "/home/me"

    const val USER = "me"

    const val PROJECT = "/home/me/proj"

    val GUARDED_ROOTS = listOf("/mnt/share", "/net/nfs")

    fun basePolicy(projectRoot: String? = PROJECT, home: String = HOME, currentUser: String = USER) =
        SensitiveGuard.Policy(
            globs = CredentialPaths.SENSITIVE_GLOBS,
            home = home,
            currentUser = currentUser,
            projectRoot = projectRoot,
        )

    fun read(path: String): JsonObject = buildJsonObject { put("file_path", path) }

    fun bash(cmd: String): JsonObject = buildJsonObject { put("command", cmd) }

    fun edit(filePath: String, oldString: String, newString: String): JsonObject = buildJsonObject {
        put("file_path", filePath)
        put("old_string", oldString)
        put("new_string", newString)
    }
}

open class GuardProbe(protected val policy: SensitiveGuard.Policy = GuardFixture.basePolicy()) {

    protected fun read(path: String): JsonObject = GuardFixture.read(path)

    protected fun bash(cmd: String): JsonObject = GuardFixture.bash(cmd)

    protected fun edit(filePath: String, oldString: String, newString: String): JsonObject =
        GuardFixture.edit(filePath, oldString, newString)

    protected fun v(input: JsonObject, p: SensitiveGuard.Policy = policy): SensitiveGuard.Verdict =
        SensitiveGuard.evaluate(input, p).verdict

    protected fun rule(input: JsonObject, p: SensitiveGuard.Policy = policy): SecurityRule? =
        SensitiveGuard.evaluate(input, p).rule

    protected fun why(input: JsonObject, p: SensitiveGuard.Policy = policy): String =
        SensitiveGuard.evaluate(input, p).reason.orEmpty()
}
