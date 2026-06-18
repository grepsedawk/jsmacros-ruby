package grepsedawk.jsmacros.jruby

import org.jruby.embed.LocalContextScope
import org.jruby.embed.ScriptingContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM parity smoke test: proves the embed API path the mod relies on
 * (construct a [ScriptingContainer], pin the classloader, run a scriptlet,
 * marshal the result back to Java) works on JRuby 10.1.0.0.
 */
class JRubyParitySmokeTest {

    @Test
    fun runsTrivialScriptletAndMarshalsResult() {
        val instance = ScriptingContainer(LocalContextScope.SINGLETHREAD)
        instance.classLoader = javaClass.classLoader
        try {
            instance.runScriptlet("p \"Ruby Pre-Loaded\"")
            val result = instance.runScriptlet("1 + 2")
            assertEquals(3L, (result as Number).toLong())
        } finally {
            instance.terminate()
        }
    }
}
