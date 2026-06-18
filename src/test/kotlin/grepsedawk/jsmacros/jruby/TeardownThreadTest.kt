package grepsedawk.jsmacros.jruby

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class TeardownThreadTest {

    companion object {
        // Boot+terminate one runtime so JRuby class-loading is warm; then the only cost left in
        // an unused-context close is the wasteful Ruby.newInstance the guard is meant to skip,
        // keeping the timing assertion order-independent.
        @JvmStatic
        @BeforeAll
        fun warmJRuby() {
            InteropRig().use { it.eval("1 + 1") }
        }
    }

    private fun waitUntilDead(thread: Thread, deadlineMs: Long): Boolean {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (!thread.isAlive) return true
            Thread.sleep(10)
        }
        return !thread.isAlive
    }

    // A CPU-bound Ruby loop is the rosegold idiom; closing the context must stop it.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun closingContextStopsCpuBoundRubyThread() {
        InteropRig().use { rig ->
            rig.eval(
                """
                ${'$'}started = false
                ${'$'}t = Thread.new { ${'$'}started = true; i = 0; while true; i += 1; end }
                sleep 0.01 until ${'$'}started
                ${'$'}native = ${'$'}t.to_java.getNativeThread
                """.trimIndent()
            )
            val native = rig.eval("\$native") as Thread?
            assertNotNull(native, "should have captured the native thread")
            assertTrue(native!!.isAlive, "thread should be alive before teardown")

            rig.ctx.closeContext()

            assertTrue(
                waitUntilDead(native, 5_000),
                "CPU-bound Ruby thread should be dead after closeContext()"
            )
        }
    }

    // Teardown must never hang even on a busy thread: the bounded join has a deadline.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun closingContextCompletesPromptlyEvenWithBusyThread() {
        InteropRig().use { rig ->
            rig.eval(
                """
                ${'$'}started = false
                ${'$'}t = Thread.new { ${'$'}started = true; i = 0; while true; i += 1; end }
                sleep 0.01 until ${'$'}started
                """.trimIndent()
            )
            val start = System.currentTimeMillis()
            rig.ctx.closeContext()
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 3_000,
                "closeContext() must complete promptly (bounded join), took ${elapsed}ms"
            )
        }
    }

    // Closing a context whose script never ran must not force-boot a runtime. With JRuby warmed
    // (see @BeforeAll), booting a fresh runtime still costs ~200ms+, while the guarded skip is
    // ~1ms; 100ms cleanly separates them.
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun closingUnusedContextDoesNotForceBootRuntime() {
        InteropRig().use { rig ->
            assertFalse(
                rig.container.provider.isRuntimeInitialized,
                "precondition: runtime not yet initialized"
            )
            val start = System.currentTimeMillis()
            rig.ctx.closeContext()
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 100,
                "closing an unused context must not boot a runtime (took ${elapsed}ms)"
            )
        }
    }
}
