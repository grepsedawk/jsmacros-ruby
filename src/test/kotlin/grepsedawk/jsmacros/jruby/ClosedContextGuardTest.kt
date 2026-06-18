package grepsedawk.jsmacros.jruby

import org.jruby.RubyMethod
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.jsmacros.core.MethodWrapper

class ClosedContextGuardTest {

    @Suppress("UNCHECKED_CAST")
    private fun InteropRig.wrap(script: String): MethodWrapper<Any?, Any?, Any?, *> {
        val m = eval(script) as RubyMethod
        return wrapper.methodToJava<Any?, Any?, Any?>(m) as MethodWrapper<Any?, Any?, Any?, *>
    }

    // rosegold closes the context from inside a Ruby thread while other registered event callbacks
    // may still fire. Such a callback must be skipped, not run against the torn-down runtime.
    @Test
    fun callbackRunsBeforeCloseAndIsSkippedAfter() {
        InteropRig().use { rig ->
            rig.eval("\$ran = 0")
            val mw = rig.wrap("def cb; \$ran += 1; end; method(:cb)")

            mw.run()
            assertEquals(1L, (rig.eval("\$ran") as Number).toLong(), "callback runs while context is open")

            rig.ctx.closeContext()
            // Without the guard this dispatch hits callFn on a dead runtime and throws.
            assertDoesNotThrow { mw.run() }
        }
    }

    @Test
    fun predicateReturnsFalseAfterClose() {
        InteropRig().use { rig ->
            val mw = rig.wrap("def yes(x); true; end; method(:yes)")

            assertEquals(true, mw.test(1), "predicate evaluates while context is open")

            rig.ctx.closeContext()
            assertDoesNotThrow { assertEquals(false, mw.test(1)) }
        }
    }
}
