import me.modmuss50.mpp.ReleaseType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("xyz.wagyourtail.unimined") version "1.4.2-SNAPSHOT"
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mod.publish)
}

val modId: String by project
val modName: String by project
val mavenGroup = project.property("maven_group") as String
val archivesBaseName = project.property("archives_base_name") as String
val jrubyVersion = libs.versions.jruby.get()

version = project.property("version") as String
group = mavenGroup

base {
    archivesName.set(archivesBaseName)
}

val javaVersion = libs.versions.java.get().toInt()

java {
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.wagyourtail.xyz/releases")
    maven("https://maven.wagyourtail.xyz/snapshots")
    maven("https://libraries.minecraft.net/")
    mavenCentral()
}

// No-remap: we compile against mojmap names and ship them as-is, just like the core mod.
unimined.minecraft {
    version(libs.versions.minecraft.get())
    side("client")

    mappings {
        mojmap()
    }

    fabric {
        loader(libs.versions.fabric.loader.get())
    }

    defaultRemapJar = false
    runs.off = true
}

// JRuby runtime, nested into the mod jar via Fabric jar-in-jar.
val jrubyJar by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    // jsmacros core API — provided by the base mod at runtime, so compile-only.
    compileOnly(libs.jsmacros.core)
    compileOnly(libs.jb.annotations)

    // fabric-language-kotlin provides the Kotlin stdlib + adapter at runtime (separate mod).
    "modImplementation"(libs.fabric.language.kotlin)

    // JRuby — compile against it, and nest it into the mod jar.
    compileOnly(libs.jruby)
    jrubyJar(libs.jruby)

    // Parity smoke test: run a trivial .rb through a real ScriptingContainer on the JVM.
    testImplementation(libs.jruby)
    // Interop tests build a headless Core + JRubyScriptContext to exercise RubyMethodWrapper.
    testImplementation(libs.jsmacros.core)
    // Core's constructor takes an slf4j Logger; not transitive, so pull the API for tests.
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    // Core's ConfigManager touches gson, and FWrapper/Core touch guava — provided by Minecraft at
    // runtime, so not on the test classpath. Pull them in for the headless interop rig.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.guava:guava:33.3.1-jre")
    testImplementation("it.unimi.dsi:fastutil:8.5.18")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Fabric Loader 0.19+ only adds a nested jar-in-jar to the runtime classpath if that
// nested jar contains its own fabric.mod.json. The stock jruby-complete jar has none,
// so we repackage it with a generated fabric.mod.json before nesting.
val jrubyModId = "org_jruby_jruby_complete"
val jrubyJarName = "jruby-complete-$jrubyVersion.jar"

val jrubyFmjDir = layout.buildDirectory.dir("jruby-fmj")
val writeJrubyFmj by tasks.registering {
    val out = jrubyFmjDir.get().file("fabric.mod.json").asFile
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        out.writeText(
            """
            {
              "schemaVersion": 1,
              "id": "$jrubyModId",
              "version": "$jrubyVersion",
              "name": "JRuby (bundled)",
              "environment": "*"
            }
            """.trimIndent()
        )
    }
}

val nestableJruby by tasks.registering(Jar::class) {
    dependsOn(jrubyJar, writeJrubyFmj)
    archiveFileName.set(jrubyJarName)
    destinationDirectory.set(layout.buildDirectory.dir("nestable-jruby"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(zipTree(jrubyJar.singleFile))
    from(jrubyFmjDir)
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("jrubyJar", jrubyJarName)
    inputs.property("minecraftVersion", libs.versions.minecraft.get())

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "jruby_jar" to jrubyJarName,
            "minecraft_version" to libs.versions.minecraft.get()
        )
    }
}

tasks.jar {
    archiveBaseName.set(archivesBaseName)
    dependsOn(nestableJruby)
    from(nestableJruby) {
        into("META-INF/jars")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val mcVersion = libs.versions.minecraft.get()

publishMods {
    val modrinthToken = providers.gradleProperty("modrinth_token")
        .orElse(providers.environmentVariable("MODRINTH_TOKEN"))

    modrinth("modrinthFabric") {
        projectId.set("EQoulhLS")
        accessToken.set(modrinthToken.orElse(""))
        minecraftVersions.add(mcVersion)
        modLoaders.set(listOf("fabric"))

        version.set("${project.version}+$mcVersion-fabric")
        displayName.set("JsMacros Ruby ${project.version} (fabric $mcVersion)")
        changelog.set("JsMacros Ruby ${project.version} for fabric on Minecraft $mcVersion.\nRequires JsMacros Reloaded.")
        type.set(ReleaseType.STABLE)
        file.set(tasks.jar.flatMap { it.archiveFile })

        requires { slug.set("jsmacros-reloaded") }
        requires { slug.set("fabric-language-kotlin") }
    }
}
