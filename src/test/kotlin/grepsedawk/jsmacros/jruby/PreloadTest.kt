package grepsedawk.jsmacros.jruby

import grepsedawk.jsmacros.jruby.client.JRubyExtension
import org.jruby.Ruby
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PreloadTest {

    // The preload warms JRuby class-loading but must NOT initialize the JVM-global JRuby singleton.
    // A no-arg ScriptingContainer() uses SINGLETON scope, which boots the global runtime and then
    // (on terminate) leaves it pointing at a dead runtime. The SINGLETHREAD preload gets its own
    // runtime and never touches the global. The rest of the suite only ever uses SINGLETHREAD, so
    // the global staying not-ready is a faithful signal that the preload didn't reach for it.
    @Test
    fun preloadDoesNotInitializeGlobalSingleton() {
        JRubyExtension().preload()
        assertFalse(
            Ruby.isGlobalRuntimeReady(),
            "preload must not initialize the JVM-global JRuby singleton"
        )
    }
}
