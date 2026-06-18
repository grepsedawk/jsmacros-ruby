package grepsedawk.jsmacros.jruby.language.impl

import org.jruby.embed.LocalContextScope
import org.jruby.embed.ScriptingContainer
import xyz.wagyourtail.jsmacros.core.Core
import xyz.wagyourtail.jsmacros.core.config.ScriptTrigger
import xyz.wagyourtail.jsmacros.core.event.BaseEvent
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage
import xyz.wagyourtail.jsmacros.core.language.EventContainer
import grepsedawk.jsmacros.jruby.client.JRubyExtension
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class JRubyLanguageDefinition(extension: JRubyExtension, runner: Core<*, *>) :
    BaseLanguage<ScriptingContainer, JRubyScriptContext>(extension, runner) {

    private fun runInstance(
        ctx: EventContainer<JRubyScriptContext>,
        event: BaseEvent?,
        scriptlet: (ScriptingContainer) -> Unit,
        cwd: Path?
    ) {
        // One fresh SINGLETHREAD container per dispatch: clean per-script isolation and teardown,
        // at ~180ms JRuby boot per dispatch (vs ~1ms to reuse a warm runtime). Long-running scripts
        // like rosegold pay it once and reuse this container for all in-process callbacks, so it is
        // not a hot path. A shared/pooled runtime would only help scripts bound to high-frequency
        // events and is deferred — serializing a shared mutable runtime is a concurrency redesign.
        val instance = ScriptingContainer(LocalContextScope.SINGLETHREAD)
        instance.classLoader = JRubyExtension::class.java.classLoader
        ctx.ctx.setContext(instance)

        if (cwd != null) {
            instance.currentDirectory = cwd.toString()
        }

        retrieveLibs(ctx.ctx).forEach { (name, lib) ->
            // "Time" is a built-in Ruby class; expose jsmacros' Time library under FTime instead.
            val bindName = if ("Time" == name) "FTime" else name
            instance.put(bindName, lib)
        }
        instance.put("event", event)
        instance.put("file", ctx.ctx.file)
        instance.put("context", ctx)

        scriptlet(instance)
    }

    override fun exec(ctx: EventContainer<JRubyScriptContext>, macro: ScriptTrigger, event: BaseEvent?) {
        val file = ctx.ctx.file!!
        runInstance(ctx, event, { instance ->
            Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { reader: Reader ->
                instance.runScriptlet(reader, file.absolutePath)
            }
        }, parentPathOf(file))
    }

    override fun exec(ctx: EventContainer<JRubyScriptContext>, lang: String, script: String, event: BaseEvent?) {
        val file = ctx.ctx.file
        runInstance(ctx, event, { instance ->
            if (file != null) {
                instance.runScriptlet(StringReader(script), file.absolutePath)
            } else {
                instance.runScriptlet(script)
            }
        }, parentPathOf(file))
    }

    override fun createContext(event: BaseEvent?, path: File?): JRubyScriptContext =
        JRubyScriptContext(runner, event, path)

    companion object {
        private fun parentPathOf(f: File?): Path? = f?.parentFile?.toPath()
    }
}
