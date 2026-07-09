import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.crowdstrike"
version = "3.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val ideaTransformDir: File? = gradle.gradleUserHomeDir.resolve("caches/9.0.0/transforms")
    .listFiles()
    ?.flatMap { it.resolve("transformed").listFiles()?.toList() ?: emptyList() }
    ?.firstOrNull { it.name.startsWith("ideaIU-2025.2.4") }

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("org.intellij.plugins.markdown")
    }

    implementation("org.semver4j:semver4j:5.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("junit:junit:4.13.2")
    // util-8.jar contains kotlinx-coroutines-core:1.10.1-intellij-4 (JetBrains-patched build).
    // IntelliJ 2025.2.4 internals call BuildersKt.runBlockingWithParallelismCompensation() which
    // only exists in the -intellij patched build, not any standard Maven Central release.
    // util-8.jar must be first so its patched classes take precedence over standard coroutines
    // pulled transitively by mockk. This artifact is not published to any external repo — it only
    // exists inside the IDE distribution extracted by Gradle, so we must reference it directly.
    if (ideaTransformDir != null) {
        testImplementation(files(
            ideaTransformDir.resolve("lib/util-8.jar"),
            ideaTransformDir.resolve("lib/testFramework.jar"),
            ideaTransformDir.resolve("plugins/junit/lib/junit.jar")
        ))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
            untilBuild = provider { null }
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    test {
    }

    buildSearchableOptions {
        enabled = false
    }

    prepareSandbox {
        doLast {
            // WORKAROUND: build a real coroutines javaagent JAR from util-8.jar.
            // The platform plugin generates a 275-byte stub with the right manifest but the
            // Premain class (kotlinx.coroutines.debug.internal.AgentPremain) isn't on the
            // agent's own classpath, so the JVM rejects it at startup. We extract the debug
            // classes from util-8.jar and re-bundle them with the stub's manifest.
            val util8 = ideaTransformDir?.resolve("lib/util-8.jar") ?: return@doLast
            val stub = layout.buildDirectory.file("coroutines-javaagent.jar").get().asFile
            if (!util8.exists() || !stub.exists()) return@doLast

            val manifest = JarFile(stub).use { it.manifest }.also {
                it.mainAttributes.putValue("Premain-Class", "kotlinx.coroutines.debug.internal.AgentPremain")
            }
            val tmp = stub.resolveSibling("coroutines-javaagent.tmp.jar")
            JarOutputStream(tmp.outputStream(), manifest).use { out ->
                JarFile(util8).use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("kotlinx/coroutines/debug") }
                        .forEach { entry ->
                            out.putNextEntry(ZipEntry(entry.name))
                            jar.getInputStream(entry).copyTo(out)
                            out.closeEntry()
                        }
                }
            }
            stub.delete()
            tmp.renameTo(stub)
        }
    }

    runIde {
        // Open the sample project on launch so the plugin has files to analyse immediately
        args(layout.projectDirectory.dir("testData/sample-project").asFile.absolutePath)
    }
}

// mockk pulls kotlinx-coroutines-bom:1.6.4 which pins coroutines below the minimum required
// by IntelliJ 2025.2's runtime (needs getEnableCreationStackTraces and install$kotlinx_coroutines_core).
configurations.all {
    resolutionStrategy.force(
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0"
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
