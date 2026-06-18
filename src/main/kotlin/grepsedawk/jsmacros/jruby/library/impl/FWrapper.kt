package grepsedawk.jsmacros.jruby.library.impl

import org.jruby.RubyMethod
import org.jruby.embed.ScriptingContainer
import org.jruby.javasupport.JavaUtil
import org.jruby.runtime.ThreadContext
import org.jruby.runtime.builtin.IRubyObject
import xyz.wagyourtail.jsmacros.core.MethodWrapper
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage
import xyz.wagyourtail.jsmacros.core.library.IFWrapper
import xyz.wagyourtail.jsmacros.core.library.Library
import xyz.wagyourtail.jsmacros.core.library.PerExecLanguageLibrary
import grepsedawk.jsmacros.jruby.language.impl.JRubyLanguageDefinition
import grepsedawk.jsmacros.jruby.language.impl.JRubyScriptContext

@Library(value = "JavaWrapper", languages = [JRubyLanguageDefinition::class])
class FWrapper(
    context: JRubyScriptContext,
    language: Class<out BaseLanguage<ScriptingContainer, JRubyScriptContext>>
) : PerExecLanguageLibrary<ScriptingContainer, JRubyScriptContext>(context, language), IFWrapper<RubyMethod> {

    override fun <A, B, R> methodToJava(c: RubyMethod): MethodWrapper<A, B, R, *> =
        RubyMethodWrapper(c, true, ctx)

    override fun <A, B, R> methodToJavaAsync(c: RubyMethod): MethodWrapper<A, B, R, *> =
        RubyMethodWrapper(c, false, ctx)

    override fun stop() {
        ctx.closeContext()
    }

    private class RubyMethodWrapper<T, U, R>(
        private val fn: RubyMethod,
        private val await: Boolean,
        ctx: JRubyScriptContext
    ) : MethodWrapper<T, U, R, JRubyScriptContext>(ctx) {

        private fun callFn(vararg params: Any?): Any? =
            callFn(JAVA_RESULT, *params)

        private fun <X> callFn(extract: (IRubyObject) -> X, vararg params: Any?): X {
            val threadContext = ctx.context.provider.runtime.currentContext
            threadContext.pushNewScope(threadContext.currentStaticScope)
            try {
                val rubyObjects = trimToArity(JavaUtil.convertJavaArrayToRuby(threadContext.runtime, params), threadContext)
                return extract(fn.call(threadContext, rubyObjects, threadContext.frameBlock))
            } finally {
                threadContext.popScope()
            }
        }

        // JsMacros dispatches the full SAM arg count (e.g. 2 for an accept(t,u) event), but a
        // strict callable that declares fewer required params (a `def foo(event)` method object)
        // raises ArgumentError when handed extras. Trim to what it can accept; variadic callables
        // (negative arity) take everything.
        private fun trimToArity(args: Array<IRubyObject>, threadContext: ThreadContext): Array<IRubyObject> {
            val arity = fn.arity(threadContext).value.toInt()
            if (arity < 0 || arity >= args.size) return args
            return args.copyOfRange(0, arity)
        }

        private fun innerAccept(vararg params: Any?) {
            // rosegold closes the context from inside a Ruby thread while other event callbacks
            // may still fire; skip them rather than run on a torn-down runtime.
            if (ctx.isContextClosed) return
            if (await) {
                innerApply<Any?>(*params)
                return
            }

            val t = Thread({
                ctx.bindThread(Thread.currentThread())
                try {
                    // Re-check: the context can close between this dispatch and the thread running.
                    if (!ctx.isContextClosed) callFn(*params)
                } catch (ex: Throwable) {
                    ctx.runner.profile.logError(ex)
                } finally {
                    ctx.unbindThread(Thread.currentThread())
                    ctx.runner.profile.joinedThreadStack.remove(Thread.currentThread())
                    ctx.releaseBoundEventIfPresent(Thread.currentThread())
                }
            }, "JRuby-JavaWrapper")
            t.isDaemon = true
            t.start()
        }

        @Suppress("UNCHECKED_CAST")
        private fun <R2> innerApply(vararg params: Any?): R2 =
            invoke(JAVA_RESULT, *params) as R2

        // Predicates extract truthiness from the raw Ruby value; only nil/false are falsey, so a
        // numeric/object/empty-string return is true. Extracting before toJava also avoids the
        // null (nil) / ClassCastException (non-Boolean) that a Boolean cast on toJava would hit.
        private fun innerTest(vararg params: Any?): Boolean {
            if (ctx.isContextClosed) return false
            return invoke(RUBY_TRUTH, *params)
        }

        private fun <X> invoke(extract: (IRubyObject) -> X, vararg params: Any?): X {
            if (ctx.boundThreads.contains(Thread.currentThread())) {
                return callFn(extract, *params)
            }

            var bound = false
            try {
                ctx.bindCallerThread()
                bound = true
                if (ctx.runner.profile.checkJoinedThreadStack()) {
                    ctx.runner.profile.joinedThreadStack.add(Thread.currentThread())
                }
                return callFn(extract, *params)
            } catch (ex: Throwable) {
                throw RuntimeException(ex)
            } finally {
                if (bound) {
                    ctx.releaseBoundEventIfPresent(Thread.currentThread())
                    ctx.unbindThread(Thread.currentThread())
                    ctx.runner.profile.joinedThreadStack.remove(Thread.currentThread())
                }
            }
        }

        override fun accept(t: T) {
            innerAccept(t)
        }

        override fun accept(t: T, u: U) {
            innerAccept(t, u)
        }

        override fun apply(t: T): R = innerApply(t)

        override fun apply(t: T, u: U): R = innerApply(t, u)

        override fun test(t: T): Boolean = innerTest(t)

        override fun test(t: T, u: U): Boolean = innerTest(t, u)

        override fun run() {
            innerAccept()
        }

        override fun compare(o1: T, o2: T): Int {
            val result = innerApply<Any?>(o1, o2)
            if (result !is Number) {
                throw ClassCastException("Ruby comparator must return a numeric value, got: $result")
            }
            return result.toInt()
        }

        override fun get(): R = innerApply()

        private companion object {
            val JAVA_RESULT: (IRubyObject) -> Any? = { it.toJava(Any::class.java) }
            val RUBY_TRUTH: (IRubyObject) -> Boolean = { it.isTrue }
        }
    }
}
