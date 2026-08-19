package dev.lain.claudejb.permission

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Generated coverage for the guard's **second axis** — the irreversible action — and for code execution and the
 * version-control safeguards, which is the half [SensitiveGuardFuzzTest] does not cover.
 *
 * ### Why this is a file of its own rather than more of that one
 * The two files answer the two things the guard is for, and they are different questions. That one asks whether
 * an **attacker** can reach something worth stealing: its corpora are credential globs, offensive tooling, drop
 * sites, and every way of spelling a path so a matcher misses it. This one asks whether an **irreversible
 * action** can happen without anyone being asked — `terraform destroy`, `DROP DATABASE`, `git push --force` —
 * plus the two families that make a machine run someone else's code, now (`npm install`) or after the session
 * ends (a cron entry, a git hook).
 *
 * The second axis needs no attacker at all: a misread instruction is enough, which is why it exists. But it does
 * not stop needing one either — under prompt injection the command text is written by the attacker, so a
 * destructive vector has to survive the same de-obfuscation a credential read does. That is why the positives
 * below are composed through [scramble] rather than asserted in their tidy spelling.
 *
 * ### Two properties per case, and the second is the one that matters
 * Every positive asserts DENY **and the rule that owns the vector**. A verdict-only assertion goes green when
 * some other rule happens to fire, and here that is not hypothetical: these commands are dense with paths, so an
 * `rm -rf` is also a shell write and a `dd of=/dev/sda` is also a device. A fixture that satisfies two rules
 * tests neither, so every base is chosen to trip exactly one — and the ones that could not be are absent rather
 * than quietly asserted against the wrong rule, with the reason recorded at the corpus.
 *
 * Every negative asserts **that rule did not fire**, not ALLOW. Several counterparts are legitimately caught by
 * a different rule (`rm -rf build/` is a shell write wherever it points), so demanding ALLOW would be demanding
 * a hole.
 */
// The corpora below are shouted (`DESTRUCTIVE`, `EXECUTION`, `BENIGN`) because that is what they are: fixed
// inputs this file generates FROM, not locals. Same reasoning, and the same pair of suppressions, as
// `SensitiveGuardFuzzTest` — the naming of a fuzz corpus is not the sort of thing a linter decides for the
// guard's own tests, and renaming them to read like variables would hide what they are.
@Suppress("VariableNaming", "ktlint:standard:property-naming")
class DestructiveVectorFuzzTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    /**
     * A light de-obfuscation layer: case scrambling, then an empty quote pair inside one word.
     *
     * Deliberately smaller than [SensitiveGuardFuzzTest]'s composer, and not shared with it. Two reasons, and
     * neither is laziness: the trick set there is tuned to the credential corpus it grew up with (one of its
     * tricks stashes a `.ssh` path in a variable, which means nothing here), and a shared generator would couple
     * the two axes so that tightening one corpus perturbs the other's cases. What both files DO share is the
     * property being tested — the guard matches the peeled command, not the typed one.
     */
    private fun Random.scramble(base: String): String {
        val cased = if (nextBoolean()) {
            base.map { if (nextBoolean()) it.uppercaseChar() else it.lowercaseChar() }.joinToString("")
        } else {
            base
        }
        val words = cased.split(" ").filter { it.isNotEmpty() }.toMutableList()
        if (words.isNotEmpty() && nextBoolean()) {
            val at = words.indices.random(this)
            val word = words[at]
            if (word.length >= 2) {
                val pos = nextInt(1, word.length)
                words[at] = word.substring(0, pos) + listOf("''", "\"\"").random(this) + word.substring(pos)
            }
        }
        return words.joinToString(" ")
    }

    /** One base per vector, each provably tripping ONE rule — see the class doc for what was excluded and why. */
    private val DESTRUCTIVE: List<Pair<SecurityRule, List<String>>> = listOf(
        SecurityRule.DESTRUCTIVE_IAC to listOf(
            "terraform destroy",
            "terraform apply -auto-approve",
            "terraform state rm aws_instance.web",
            "terragrunt destroy",
            "tofu destroy",
            "pulumi destroy --yes",
        ),
        SecurityRule.DESTRUCTIVE_ORCHESTRATION to listOf(
            "kubectl delete namespace prod",
            "kubectl delete pods --all",
            "kubectl drain node-1",
            "helm uninstall checkout",
        ),
        SecurityRule.DESTRUCTIVE_CLOUD to listOf(
            "aws s3 rb s3://prod-assets --force",
            "aws rds delete-db-instance --db-instance-identifier prod",
            "aws ec2 terminate-instances --instance-ids i-0abc",
            "gcloud compute instances delete web-1",
            "az group delete --name prod",
        ),
        SecurityRule.DESTRUCTIVE_DATABASE to listOf(
            "psql -c 'DROP DATABASE prod'",
            "mysql -e 'TRUNCATE TABLE users'",
            "mysqladmin drop prod",
            "mongosh --eval 'db.dropDatabase()'",
            "redis-cli FLUSHALL",
        ),
        SecurityRule.DESTRUCTIVE_CONTAINER to listOf(
            "docker system prune -a",
            "docker volume rm pgdata",
            "docker rm -f web",
            "docker-compose down -v",
        ),
        SecurityRule.DESTRUCTIVE_GIT to listOf(
            "git push --force origin main",
            "git reset --hard HEAD~3",
            "git clean -fdx",
            "git filter-branch --tree-filter true HEAD",
            "git branch -D release",
        ),
        // No `dd of=/dev/…` and no `mkfs /dev/sdb`: a device node is judged by SYSTEM_DEVICE first, which is the
        // correct and stronger wording, so those cases belong to the device test rather than to this one.
        SecurityRule.DESTRUCTIVE_FILESYSTEM to listOf(
            "rm -rf /var/lib/elasticsearch",
            "rm -rf ~/Documents",
            "mkfs.ext4 disk1",
            "shred -u ledger.dat",
        ),
    )

    private val EXECUTION: List<Pair<SecurityRule, List<String>>> = listOf(
        // No `curl … | bash`: that is a remote-code pipe and SECRET_DUMPING_COMMANDS owns it, earlier in the
        // ordering. Listing it under installs would have tested the wrong rule.
        SecurityRule.PACKAGE_INSTALL_HOOK to listOf(
            "npm install left-pad",
            "pnpm add lodash",
            "pip install requests",
            "pip3 install urllib3",
            "gem install rails",
            "cargo install ripgrep",
            "composer install",
        ),
        SecurityRule.PERSISTENCE_MECHANISM to listOf(
            "crontab payload.cron",
            "systemctl enable beacon.timer",
            "git config core.hooksPath hooks",
        ),
        SecurityRule.CODE_INJECTION to listOf(
            "LD_PRELOAD=./hook.so id",
            "DYLD_INSERT_LIBRARIES=./hook.dylib ls",
            "LD_LIBRARY_PATH=./lib ldd bin",
        ),
        // A credential named explicitly is deliberately absent: CredentialPaths would report it as the credential
        // it is, which is the more informative answer and a different rule.
        SecurityRule.VCS_PROTECTION_BYPASS to listOf(
            "git add -f build/out.bin",
            "git stage --force dist/bundle.js",
            "git commit --no-verify -m wip",
            "git push --no-verify origin main",
        ),
    )

    /** The ordinary counterpart of every vector — the half that decides whether anyone keeps the guard on. */
    private val BENIGN: List<Pair<SecurityRule, List<String>>> = listOf(
        SecurityRule.DESTRUCTIVE_IAC to listOf("terraform plan", "terraform init", "terraform validate"),
        SecurityRule.DESTRUCTIVE_ORCHESTRATION to listOf(
            "kubectl get pods -n prod",
            "kubectl apply -f k8s/",
            "helm upgrade checkout ./chart",
        ),
        SecurityRule.DESTRUCTIVE_CLOUD to listOf(
            "aws s3 ls s3://prod-assets",
            "aws ec2 describe-instances",
            "gcloud compute instances list",
        ),
        SecurityRule.DESTRUCTIVE_DATABASE to listOf(
            "psql -c 'SELECT count(*) FROM users'",
            "mysql -e 'SHOW TABLES'",
            "redis-cli INFO",
        ),
        SecurityRule.DESTRUCTIVE_CONTAINER to listOf(
            "docker ps -a",
            "docker build -t app .",
            "docker-compose up -d",
        ),
        SecurityRule.DESTRUCTIVE_GIT to listOf("git status", "git commit -m wip", "git push origin main", "git pull"),
        SecurityRule.DESTRUCTIVE_FILESYSTEM to listOf("rm -rf build/", "rm -rf node_modules", "rm -rf target"),
        SecurityRule.PACKAGE_INSTALL_HOOK to listOf(
            "npm test",
            "npm run build",
            "pip list",
            "cargo build --release",
            "go test ./...",
        ),
        SecurityRule.PERSISTENCE_MECHANISM to listOf("crontab -l", "systemctl status app", "git config user.name"),
        SecurityRule.VCS_PROTECTION_BYPASS to listOf("git add .", "git add -A", "git commit -a -m wip"),
    )

    @Test
    fun `every destructive vector, obfuscated at random, is refused BY ITS OWN RULE`() {
        val rng = Random(20260819L + 1)
        repeat(700) {
            val (rule, bases) = DESTRUCTIVE.random(rng)
            val base = bases.random(rng)
            val cmd = rng.scramble(base)
            val decision = SensitiveGuard.evaluate(bash(cmd), policy)
            assertEquals(SensitiveGuard.Verdict.DENY, decision.verdict, "'$cmd' (from '$base')")
            assertEquals(rule, decision.rule, "'$cmd' (from '$base') tripped the wrong rule")
        }
    }

    @Test
    fun `every code-execution and version-control vector is refused BY ITS OWN RULE`() {
        val rng = Random(20260819L + 2)
        repeat(600) {
            val (rule, bases) = EXECUTION.random(rng)
            val base = bases.random(rng)
            val cmd = rng.scramble(base)
            val decision = SensitiveGuard.evaluate(bash(cmd), policy)
            assertEquals(SensitiveGuard.Verdict.DENY, decision.verdict, "'$cmd' (from '$base')")
            assertEquals(rule, decision.rule, "'$cmd' (from '$base') tripped the wrong rule")
        }
    }

    @Test
    fun `the ordinary counterpart of every vector never trips that vector's rule`() {
        val rng = Random(20260819L + 3)
        repeat(700) {
            val (rule, bases) = BENIGN.random(rng)
            val cmd = bases.random(rng)
            assertNotEquals(rule, SensitiveGuard.evaluate(bash(cmd), policy).rule, cmd)
        }
    }
}
