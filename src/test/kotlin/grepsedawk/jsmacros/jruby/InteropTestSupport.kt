package grepsedawk.jsmacros.jruby

import grepsedawk.jsmacros.jruby.language.impl.JRubyLanguageDefinition
import grepsedawk.jsmacros.jruby.language.impl.JRubyScriptContext
import grepsedawk.jsmacros.jruby.library.impl.FWrapper
import org.jruby.embed.LocalContextScope
import org.jruby.embed.ScriptingContainer
import org.slf4j.LoggerFactory
import xyz.wagyourtail.jsmacros.core.Core
import xyz.wagyourtail.jsmacros.core.config.BaseProfile
import xyz.wagyourtail.jsmacros.core.config.ScriptTrigger
import xyz.wagyourtail.jsmacros.core.event.BaseEventRegistry
import java.io.File
import java.nio.file.Files

/**
 * Headless interop rig: builds a real [Core] + [JRubyScriptContext] (no Minecraft) wired to a
 * live [ScriptingContainer], so tests can exercise the real [FWrapper] dispatch paths. The
 * current thread is pre-bound to the context, so dispatch takes innerApply's bound-thread
 * short-circuit and never touches Minecraft-coupled threading.
 */
class InteropRig : AutoCloseable {

    private class StubRegistry(runner: Core<*, *>) : BaseEventRegistry(runner) {
        override fun addScriptTrigger(t: ScriptTrigger?) {}
        override fun removeScriptTrigger(t: ScriptTrigger?): Boolean = false
        override fun getScriptTriggers(): MutableList<ScriptTrigger> = mutableListOf()
    }

    private class StubProfile(runner: Core<*, *>, logger: org.slf4j.Logger) :
        BaseProfile(runner, logger) {
        @Volatile var lastError: Throwable? = null
        override fun logError(ex: Throwable?) { lastError = ex }
        override fun checkJoinedThreadStack(): Boolean = false
    }

    private val tmp = Files.createTempDirectory("jruby-interop").toFile()
    val core: Core<*, *> = Core(
        { runner -> StubRegistry(runner) },
        { runner, logger -> StubProfile(runner, logger) },
        File(tmp, "config"),
        File(tmp, "macros"),
        LoggerFactory.getLogger("interop-test")
    )

    val container: ScriptingContainer = ScriptingContainer(LocalContextScope.SINGLETHREAD).apply {
        classLoader = javaClass.classLoader
    }

    val ctx: JRubyScriptContext = JRubyScriptContext(core, null, null).apply {
        setContext(container)
        setMainThread(Thread.currentThread())
        bindThread(Thread.currentThread())
    }

    val wrapper = FWrapper(ctx, JRubyLanguageDefinition::class.java)

    fun eval(script: String): Any? = container.runScriptlet(script)

    override fun close() {
        try { container.terminate() } catch (_: Throwable) {}
        try { tmp.deleteRecursively() } catch (_: Throwable) {}
    }
}
