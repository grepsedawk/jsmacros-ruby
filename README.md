# JsMacros Ruby

JRuby scripting language support for [JsMacros Reloaded](https://modrinth.com/mod/jsmacros-reloaded), packaged as a standalone Fabric mod.

It lets you write JsMacros scripts in Ruby (`.rb`). The mod is discovered at runtime by JsMacros Reloaded through the `jsmacros` Fabric entrypoint, and the JRuby runtime is embedded via Fabric's jar-in-jar.

## Requirements

- Minecraft 26.1.2 (Fabric)
- [JsMacros Reloaded](https://modrinth.com/mod/jsmacros-reloaded) 2.x
- [fabric-language-kotlin](https://modrinth.com/mod/fabric-language-kotlin)

Drop all three jars into your `mods/` folder. JRuby itself ships inside this mod, so there is nothing else to install.

## Building

The Gradle 8.14 launcher must run on JDK 21; the Java toolchain compiles with JDK 25.

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

Output: `build/libs/jsmacros-ruby-26.1.2-1.0.0.jar`.

## License

MIT — see [LICENSE](LICENSE).

## Credits

- [JsMacros](https://github.com/wagyourtail/JsMacros) by WagYourTail — the scripting mod this builds on.
