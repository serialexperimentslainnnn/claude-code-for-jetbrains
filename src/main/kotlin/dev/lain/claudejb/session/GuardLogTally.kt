package dev.lain.claudejb.session

import java.util.concurrent.atomic.AtomicInteger

class GuardLogTally {

    private val offered = AtomicInteger()

    private val refused = AtomicInteger()

    val recorded: Int get() = offered.get()

    val dropped: Int get() = refused.get()

    fun submitted(accepted: Boolean) {
        offered.incrementAndGet()
        if (!accepted) refused.incrementAndGet()
    }
}
