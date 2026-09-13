package dev.lain.claudejb.controller.mcp.tools.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class TestRow(val test: String, val message: String, val at: String)

internal class TestOutcome(
    val exitCode: Int,
    val tracked: Boolean,
    val passed: Int,
    val failed: Int,
    val ignored: Int,
    val failures: List<TestRow>,
)

internal class TestRunListener(private val tail: OutputTail) : SMTRunnerEventsAdapter() {

    @Volatile
    var handler: ProcessHandler? = null

    @Volatile
    private var root: SMTestProxy.SMRootTestProxy? = null

    val finished = CompletableDeferred<Unit>()
    private val passed = AtomicInteger()
    private val failed = AtomicInteger()
    private val ignored = AtomicInteger()
    private val failures = CopyOnWriteArrayList<TestRow>()

    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
        if (root == null && testsRoot.handler === handler) root = testsRoot
    }

    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        if (testsRoot === root) finished.complete(Unit)
    }

    override fun onTestFinished(test: SMTestProxy) {
        if (!mine(test) || !test.isPassed) return
        passed.incrementAndGet()
        tail.line("PASS ${test.presentableName}")
    }

    override fun onTestFailed(test: SMTestProxy) {
        if (!mine(test)) return
        failed.incrementAndGet()
        val message = test.errorMessage ?: ""
        if (failures.size < MAX_FAILURES) failures += TestRow(test.presentableName, message, frame(test.stacktrace))
        tail.line("FAIL ${test.presentableName}" + if (message.isEmpty()) "" else " — ${message.lineSequence().first()}")
    }

    override fun onTestIgnored(test: SMTestProxy) {
        if (!mine(test)) return
        ignored.incrementAndGet()
        tail.line("SKIP ${test.presentableName}")
    }

    fun outcome(exitCode: Int): TestOutcome =
        TestOutcome(exitCode, root != null, passed.get(), failed.get(), ignored.get(), failures.toList())

    private fun mine(test: SMTestProxy): Boolean = root != null && test.root === root

    private fun frame(stacktrace: String?): String {
        val lines = stacktrace?.lines()?.map(String::trim).orEmpty()
        return lines.firstOrNull { it.startsWith("at ") }?.removePrefix("at ") ?: lines.firstOrNull { it.isNotEmpty() } ?: ""
    }

    private companion object {
        const val MAX_FAILURES = 200
    }
}
