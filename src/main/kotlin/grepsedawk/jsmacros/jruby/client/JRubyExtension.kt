package grepsedawk.jsmacros.jruby.client

import org.jruby.embed.EvalFailedException
import org.jruby.embed.LocalContextScope
import org.jruby.embed.ScriptingContainer
import org.jruby.exceptions.RaiseException
import org.jruby.runtime.builtin.IRubyObject
import xyz.wagyourtail.jsmacros.core.Core
import xyz.wagyourtail.jsmacros.core.extensions.LanguageExtension
import xyz.wagyourtail.jsmacros.core.extensions.LibraryExtension
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary
import grepsedawk.jsmacros.jruby.language.impl.JRubyLanguageDefinition
import grepsedawk.jsmacros.jruby.library.impl.FWrapper
import java.io.File

class JRubyExtension : LanguageExtension, LibraryExtension {

    private var languageDefinition: JRubyLanguageDefinition? = null

    override fun getExtensionName(): String = "jruby"

    // Talks to a stable SPI, so it should rarely care about the core version. These bounds just
    // track the fabric.mod.json depends range (jsmacros >=2.0.0 <3.0.0): any 2.x core, re-check
    // at the 3.0 major. The "jsmacros" entrypoint discovery this relies on shipped in core 2.0.0.
    override fun minCoreVersion(): String = "2.0.0"

    override fun maxCoreVersion(): String = "3.0.0"

    override fun init(runner: Core<*, *>) {
        val t = Thread(::preload, "JRuby-Preload")
        t.isDaemon = true
        t.start()
    }

    // SINGLETHREAD, not the no-arg SINGLETON default: the warm-up gets its own runtime and never
    // leaves the JVM-global JRuby singleton pointing at a torn-down runtime. Scripts use their own
    // SINGLETHREAD containers, so the only thing actually reused is warmed JRuby class-loading.
    // try/finally so a failed scriptlet still terminates the container.
    internal fun preload() {
        val instance = ScriptingContainer(LocalContextScope.SINGLETHREAD)
        instance.classLoader = JRubyExtension::class.java.classLoader
        try {
            instance.runScriptlet("p \"Ruby Pre-Loaded\"")
        } finally {
            instance.terminate()
        }
    }

    override fun getPriority(): Int = 0

    override fun extensionMatch(file: File): LanguageExtension.ExtMatch {
        if (file.name.endsWith(".rb")) {
            return if (file.name.contains(getExtensionName())) {
                LanguageExtension.ExtMatch.MATCH_WITH_NAME
            } else {
                LanguageExtension.ExtMatch.MATCH
            }
        }
        return LanguageExtension.ExtMatch.NOT_MATCH
    }

    override fun defaultFileExtension(): String = "rb"

    @Synchronized
    override fun getLanguage(runner: Core<*, *>): BaseLanguage<*, *> {
        if (languageDefinition == null) {
            val classLoader = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = JRubyExtension::class.java.classLoader
            try {
                languageDefinition = JRubyLanguageDefinition(this, runner)
            } finally {
                Thread.currentThread().contextClassLoader = classLoader
            }
        }
        return languageDefinition!!
    }

    override fun getLibraries(): Set<Class<out BaseLibrary>> = setOf(FWrapper::class.java)

    override fun wrapException(ex: Throwable): BaseWrappedException<*>? {
        // Callbacks rethrow as RuntimeException(RaiseException) and evals as
        // EvalFailedException(RaiseException); unwrap the RaiseException from anywhere in the cause
        // chain so the Ruby file:line survives instead of a Java-only trace.
        val raise = findRaiseException(ex)
        if (raise != null) {
            val e = raise.exception
            val frames = e.backtraceElements
                .map { it.asStackTraceElement() }
                .toTypedArray()
            return BaseWrappedException(e, e.messageAsJavaString, null, buildTrace(frames))
        }
        if (ex !is EvalFailedException) return null
        val cause: Throwable = ex.cause ?: ex
        return BaseWrappedException(cause, cause.javaClass.name + ": " + cause.message, null, buildTrace(cause.stackTrace))
    }

    // Bounded against self-referential cause chains.
    private fun findRaiseException(ex: Throwable): RaiseException? =
        generateSequence(ex as Throwable?) { it.cause }
            .take(16)
            .firstOrNull { it is RaiseException } as RaiseException?

    private fun buildTrace(frames: Array<StackTraceElement>): BaseWrappedException<StackTraceElement>? {
        var head: BaseWrappedException<StackTraceElement>? = null
        for (i in frames.indices.reversed()) {
            val frame = frames[i]
            val cls = frame.className
            if ("org.jruby.embed.internal.EmbedEvalUnitImpl" == cls) {
                // upstream ran here — discard everything we've accumulated above it in the chain
                head = null
                continue
            }
            if (cls.startsWith("org.jruby")) continue
            val loc: BaseWrappedException.SourceLocation
            if ("RUBY" == cls) {
                val fileName = frame.fileName
                loc = BaseWrappedException.GuestLocation(
                    if (fileName != null) File(fileName) else null,
                    -1, -1, frame.lineNumber, -1
                )
            } else {
                loc = BaseWrappedException.HostLocation(cls + " " + frame.lineNumber)
            }
            head = BaseWrappedException(frame, frame.methodName, loc, head)
        }
        return head
    }

    override fun isGuestObject(o: Any?): Boolean = o is IRubyObject
}
