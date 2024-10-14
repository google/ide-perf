/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.intellij.platform").version("2.2.1")
    id("org.jetbrains.kotlin.jvm").version("2.0.21")
}

val isCI = System.getenv("CI") != null
val isRelease = project.findProperty("release") != null
val versionSuffix = if (isRelease) "" else "-SNAPSHOT"
val tokenProperty = "plugins.repository.token"

group = "com.google.idea.perf"
version = "1.3.2$versionSuffix"

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

tasks.withType<KotlinCompile> {
    kotlinOptions {
        languageVersion = "2.0"
        apiVersion = "2.0" // Should match the Kotlin stdlib version in IntelliJ.
        jvmTarget = "21" // Should match the JetBrains Runtime.
        allWarningsAsErrors = true
    }
}

// See https://github.com/JetBrains/intellij-platform-gradle-plugin.
intellijPlatform {
    buildSearchableOptions = false // This task is too slow.
    autoReload = false // Auto-reload does not work well for this plugin currently.

    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243" // Should be tested occasionally (see pluginVerification).
            untilBuild = provider { null } // So that we can leave the until-build blank.
        }
        changeNotes = """
            <ul>
            <li>Added support for tracing Java 21 bytecode.</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token = project.findProperty(tokenProperty) as? String
    }

    // Note: the plugin verifier does not run by default in a normal build.
    // Instead, you have to run it explicitly with: ./gradlew verifyPlugin
    pluginVerification {
        ides.recommended()

        val suppressedFailures = listOf(
            VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES, // TODO: VfsStatTreeTable uses JBTreeTable.
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES, // See CachedValueEventConsumer.
            VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES, // Using PropertiesComponent.getValues.
        )
        failureLevel = VerifyPluginTask.FailureLevel.ALL - suppressedFailures

        // Suppress false-positive NoSuchClassErrors; they are caused by the agent being
        // loaded in the boot classloader rather than the plugin classloader.
        externalPrefixes = listOf("com.google.idea.perf.agent")
    }
}

val javaAgent: Configuration by configurations.creating

tasks.withType<PrepareSandboxTask> {
    // Copy agent artifacts into the plugin home directory.
    from(javaAgent) { into("ide-perf/agent") }
}

tasks.publishPlugin {
    doFirst {
        check(isRelease) { "Must do a release build when publishing the plugin" }
        checkNotNull(token.get()) { "Must specify an upload token via -P$tokenProperty" }
    }
}

tasks.runIde {
    // Always enable assertions.
    jvmArgs("-ea")

    // Copy over some JVM args from IntelliJ.
    jvmArgs("-XX:ReservedCodeCacheSize=240m")
    jvmArgs("-XX:+UseConcMarkSweepGC")
    jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=50")
    jvmArgs("-XX:CICompilerCount=2")
    jvmArgs("-Djdk.module.illegalAccess.silent=true")
    jvmArgs("-XX:+UseCompressedOops")

    enableAgent()
}

tasks.test {
    jvmArgs("-Djdk.module.illegalAccess.silent=true") // From IntelliJ.
    testLogging.exceptionFormat = TestExceptionFormat.FULL
    testLogging.showStandardStreams = isCI
    enableAgent()
}

fun JavaForkOptions.enableAgent() {
    val atStartup = project.findProperty("loadAgentAtStartup") == "true"
    if (atStartup) {
        // Add the -javaagent startup flag.
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            javaAgent.map { file -> "-javaagent:$file" }
        })
    } else {
        // Let the agent load itself later.
        systemProperty("jdk.attach.allowAttachSelf", true)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // See task 'printProductsReleases' for available IntelliJ versions.
        intellijIdeaCommunity("2024.3")
        pluginVerifier()
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }

    javaAgent(project(":agent", "runtimeElements"))
    compileOnly(project(":agent")) // 'compileOnly' because it is put on the bootclasspath later.

    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")

    testImplementation("org.jetbrains.kotlin:kotlin-test:1.7.21")
    testImplementation("com.google.truth:truth:1.1.3")
    testImplementation("com.google.truth.extensions:truth-java8-extension:1.1.3")

    // Workaround for https://youtrack.jetbrains.com/issue/IJPL-157292.
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")

    // For simplicity, we assume all 'compileOnly' dependencies should be 'testCompileOnly' as well.
    configurations.testCompileOnly.get().extendsFrom(configurations.compileOnly.get())
}

val pluginLibs: Configuration by configurations.creating {
    extendsFrom(configurations.implementation.get())
}

sourceSets.all {
    // Reorder the classpath to better match the class loading behavior used in production.
    // Ideally we could run tests using proper classloaders (idea.run.tests.with.bundled.plugins),
    // but that will require a more complex JUnit setup (probably via BlockJUnit4ClassRunner).
    compileClasspath = pluginLibs + compileClasspath
    runtimeClasspath = pluginLibs + runtimeClasspath
}
