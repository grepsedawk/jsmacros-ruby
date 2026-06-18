package grepsedawk.jsmacros.jruby

import grepsedawk.jsmacros.jruby.client.JRubyExtension
import org.jruby.embed.EvalFailedException
import org.jruby.embed.LocalContextScope
import org.jruby.embed.ScriptingContainer
import org.jruby.exceptions.RaiseException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException
import java.io.StringReader

class WrapExceptionTest {

    private lateinit var container: ScriptingContainer

    @BeforeEach
    fun setUp() {
        container = ScriptingContainer(LocalContextScope.SINGLETHREAD)
        container.classLoader = javaClass.classLoader
    }

    @AfterEach
    fun tearDown() {
        container.terminate()
    }

    private val raisingScript = "def boom\n  raise 'kaboom'\nend\nboom\n"

    private fun evalFailureFrom(script: String): Throwable =
        runCatching { container.runScriptlet(StringReader(script), "myscript.rb") }.exceptionOrNull()!!

    private fun raiseExceptionFrom(script: String): RaiseException {
        val ex = evalFailureFrom(script)
        return when {
            ex is RaiseException -> ex
            ex.cause is RaiseException -> ex.cause as RaiseException
            else -> error("expected a RaiseException, got $ex / cause=${ex.cause}")
        }
    }

    private fun hasRubySourceLine(wrapped: BaseWrappedException<*>?): Boolean {
        var node = wrapped
        while (node != null) {
            val loc = node.location
            if (loc is BaseWrappedException.GuestLocation && loc.line >= 0) return true
            node = node.next
        }
        return false
    }

    @Test
    fun callbackRaiseExceptionWrappedInRuntimeExceptionCarriesRubyLine() {
        // The callback path (FWrapper.innerApply) rethrows as RuntimeException(RaiseException),
        // not EvalFailedException, so the Ruby line was being dropped (wrapException returned null).
        val raise = raiseExceptionFrom(raisingScript)
        val callbackError = RuntimeException(raise)

        val wrapped = JRubyExtension().wrapException(callbackError)

        assertNotNull(wrapped, "callback error wrapping a RaiseException must not be dropped")
        assertTrue(
            hasRubySourceLine(wrapped),
            "wrapped callback error must carry a Ruby source location with a line number"
        )
    }

    @Test
    fun bareRaiseExceptionCarriesRubyLine() {
        val raise = raiseExceptionFrom(raisingScript)

        val wrapped = JRubyExtension().wrapException(raise)

        assertNotNull(wrapped, "a bare RaiseException must be handled, not dropped")
        assertTrue(hasRubySourceLine(wrapped), "bare RaiseException must carry a Ruby source location")
    }

    @Test
    fun evalFailedExceptionPathStillCarriesRubyLine() {
        val evalFailed = evalFailureFrom(raisingScript)
        assertTrue(evalFailed is EvalFailedException, "expected EvalFailedException, got $evalFailed")

        val wrapped = JRubyExtension().wrapException(evalFailed)

        assertNotNull(wrapped)
        assertTrue(hasRubySourceLine(wrapped), "EvalFailedException path must still carry a Ruby line")
    }

    @Test
    fun deeplyNestedRaiseExceptionCarriesRubyLine() {
        val raise = raiseExceptionFrom(raisingScript)
        val nested = RuntimeException(IllegalStateException(raise))

        val wrapped = JRubyExtension().wrapException(nested)

        assertNotNull(wrapped, "a RaiseException nested deeper in the cause chain must still be found")
        assertTrue(hasRubySourceLine(wrapped), "nested RaiseException must carry a Ruby source location")
    }

    @Test
    fun evalFailedExceptionWithNoCauseDoesNotCrash() {
        // An EvalFailedException with a null cause must not NPE in wrapException.
        val wrapped = JRubyExtension().wrapException(EvalFailedException(null as Throwable?))

        assertNotNull(wrapped, "a causeless EvalFailedException must still produce a wrapped exception")
    }
}
