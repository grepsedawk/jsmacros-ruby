package grepsedawk.jsmacros.jruby

import org.jruby.RubyMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.jsmacros.core.MethodWrapper

class CallbackArityTest {

    @Suppress("UNCHECKED_CAST")
    private fun InteropRig.wrap(script: String): MethodWrapper<Any?, Any?, Any?, *> {
        val m = eval(script) as RubyMethod
        return wrapper.methodToJava<Any?, Any?, Any?>(m) as MethodWrapper<Any?, Any?, Any?, *>
    }

    @Test
    fun strictOneArgCallableDispatchedWithTwoArgsSucceeds() {
        InteropRig().use { rig ->
            // A strict def-style 1-arg method object (arity 1). JsMacros dispatches a 2-arg event.
            val mw = rig.wrap("def only_event(event); event.to_i + 100; end; method(:only_event)")
            val r = mw.apply(5, 999)
            assertEquals(105L, (r as Number).toLong())
        }
    }

    @Test
    fun twoArgDefineSingletonMethodDispatchedWithTwoArgsWorks() {
        InteropRig().use { rig ->
            val mw = rig.wrap("define_singleton_method(:cb) { |event, context| event.to_i + context.to_i }; method(:cb)")
            val r = mw.apply(2, 3)
            assertEquals(5L, (r as Number).toLong())
        }
    }

    @Test
    fun splatCallableDispatchedWithTwoArgsWorks() {
        InteropRig().use { rig ->
            val mw = rig.wrap("def splat(*args); args.length; end; method(:splat)")
            val r = mw.apply(1, 2)
            assertEquals(2L, (r as Number).toLong())
        }
    }

    @Test
    fun zeroArgCallableDispatchedWithZeroArgsWorks() {
        InteropRig().use { rig ->
            val mw = rig.wrap("define_singleton_method(:z) { 42 }; method(:z)")
            val r = mw.get()
            assertEquals(42L, (r as Number).toLong())
        }
    }

    @Test
    fun strictTwoArgCallableDispatchedWithTwoArgsStillReceivesBoth() {
        InteropRig().use { rig ->
            val mw = rig.wrap("def two(a, b); a.to_i * 10 + b.to_i; end; method(:two)")
            val r = mw.apply(3, 4)
            assertEquals(34L, (r as Number).toLong())
        }
    }
}
