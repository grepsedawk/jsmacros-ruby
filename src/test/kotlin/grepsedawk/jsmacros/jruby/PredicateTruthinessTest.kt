package grepsedawk.jsmacros.jruby

import org.jruby.RubyMethod
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.jsmacros.core.MethodWrapper

class PredicateTruthinessTest {

    @Suppress("UNCHECKED_CAST")
    private fun InteropRig.predicate(returning: String): MethodWrapper<Any?, Any?, Any?, *> {
        val m = eval("define_singleton_method(:p) { |x| $returning }; method(:p)") as RubyMethod
        return wrapper.methodToJava<Any?, Any?, Any?>(m) as MethodWrapper<Any?, Any?, Any?, *>
    }

    // Ruby truthiness: only nil and false are falsey; everything else (0, "", objects) is truthy.

    @Test
    fun nilIsFalse() {
        InteropRig().use { rig -> assertFalse(rig.predicate("nil").test(1)) }
    }

    @Test
    fun falseIsFalse() {
        InteropRig().use { rig -> assertFalse(rig.predicate("false").test(1)) }
    }

    @Test
    fun zeroIsTrue() {
        InteropRig().use { rig -> assertTrue(rig.predicate("0").test(1)) }
    }

    @Test
    fun emptyStringIsTrue() {
        InteropRig().use { rig -> assertTrue(rig.predicate("\"\"").test(1)) }
    }

    @Test
    fun nonEmptyStringIsTrue() {
        InteropRig().use { rig -> assertTrue(rig.predicate("\"x\"").test(1)) }
    }

    @Test
    fun arbitraryObjectIsTrue() {
        InteropRig().use { rig -> assertTrue(rig.predicate("Object.new").test(1)) }
    }

    @Test
    fun trueIsTrue() {
        InteropRig().use { rig -> assertTrue(rig.predicate("true").test(1)) }
    }
}
