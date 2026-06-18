package grepsedawk.jsmacros.jruby.language.impl

import org.jruby.embed.ScriptingContainer
import xyz.wagyourtail.jsmacros.core.Core
import xyz.wagyourtail.jsmacros.core.event.BaseEvent
import xyz.wagyourtail.jsmacros.core.language.BaseScriptContext
import java.io.File

class JRubyScriptContext(runner: Core<*, *>, event: BaseEvent?, file: File?) :
    BaseScriptContext<ScriptingContainer>(runner, event, file) {

    override fun doSubclassClose() {
        val ctx = context ?: return
        // Ruby's Thread.new spawns JRuby-runtime-owned threads that aren't
        // tracked in BaseScriptContext.threads, so closeContext's interrupt
        // wave misses them. Interrupt them here so closing the context
        // actually stops user code spawned via Thread.new.
        // Guard on isRuntimeInitialized: closing a context whose script never
        // ran must not force-boot a runtime just to walk an empty thread list.
        try {
            if (ctx.provider.isRuntimeInitialized) {
                for (rt in ctx.provider.runtime.threadService.activeRubyThreads) {
                    val nativeThread = rt.nativeThread
                    if (nativeThread != null && nativeThread !== Thread.currentThread()) {
                        nativeThread.interrupt()
                    }
                }
            }
        } catch (ex: Throwable) {
            runner.profile.logError(ex)
        }
        ctx.terminate()
    }

    override fun isMultiThreaded(): Boolean = true
}
