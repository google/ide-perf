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
import org.jetbrains.intellij.tasks.PrepareSandboxTask
import org.jetbrains.intellij.tasks.RunPluginVerifierTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

plugins {
    id("java")
    id("org.jetbrains.intellij").version("1.2.0")
    id("org.jetbrains.kotlin.jvm").version("1.5.31")
}

val isCI = System.getenv("CI") != null
val isRelease = project.findProperty("release") != null
val versionSuffix = if (isRelease) "" else "-SNAPSHOT"

group = "com.google.idea.perf"
version = "1.2.0$versionSuffix"

repositories {
    mavenCentral()
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(11))

tasks.withType<KotlinCompile> {
    kotlinOptions {
        languageVersion = "1.4"
        apiVersion = "1.4" // Should match the Kotlin stdlib version in IntelliJ.
        jvmTarget = "11" // Should match the JetBrains Runtime.
        // allWarningsAsErrors = true
        freeCompilerArgs = listOf("-Xjvm-default=enable")
    }
}

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
    pluginName.set("ide-perf")
    version.set("212.5080.55")
    downloadSources.set(!isCI)
    updateSinceUntilBuild.set(false) // So that we can leave the until-build blank.
}

tasks.buildSearchableOptions {
    // Disable the (slow) 'buildSearchableOptions' task; we don't need it yet anyway.
    enabled = false
}

tasks.patchPluginXml {
    // Should be tested occasionally (see runPluginVerifier).
    sinceBuild.set("211")
    // Should describe changes in the latest release only.
    changeNotes.set(
        """
        <ul>
        <li>Fixed the CachedValue tracer when running on IntelliJ 2021.1.</li>
        <li>The CachedValue tracer now reports how much time is spent computing each value.</li>
        </ul>
        """.trimIndent()
    )
}

// Note: the plugin verifier does not run by default in a normal build.
// Instead you have to run it explicitly with: ./gradlew runPluginVerifier
tasks.runPluginVerifier {
    downloadDir.set("$buildDir/pluginVerifier/ides")

    // See https://www.jetbrains.com/idea/download/other.html or
    // https://jb.gg/intellij-platform-builds-list for the list of platform versions.
    ideVersions.set(
        listOf(
            "211.7628.21", // Should match the since-build from plugin.xml.
            intellij.version.get() // We check the current version too for deprecations, etc.
        )
    )

    val suppressedFailures = listOf(
        RunPluginVerifierTask.FailureLevel.EXPERIMENTAL_API_USAGES, // TODO: VfsStatTreeTable uses JBTreeTable.
        RunPluginVerifierTask.FailureLevel.INTERNAL_API_USAGES // See CachedValueEventConsumer.
    )
    failureLevel.set(EnumSet.copyOf(RunPluginVerifierTask.FailureLevel.ALL - suppressedFailures))

    // Suppress false-positive NoSuchClassErrors; they are caused by the agent being
    // loaded in the boot classloader rather than the plugin classloader.
    externalPrefixes.set(listOf("com.google.idea.perf.agent"))
}

val javaAgent: Configuration by configurations.creating

tasks.withType<PrepareSandboxTask> {
    // Copy agent artifacts into the plugin home directory.
    val agentDir = "${pluginName.get()}/agent"
    from(javaAgent) { into(agentDir) }
}

tasks.publishPlugin {
    val tokenProp = "plugins.repository.token"
    val theToken = project.findProperty(tokenProp) as? String
    token.set(theToken)
    doFirst {
        check(isRelease) { "Must do a release build when publishing the plugin" }
        checkNotNull(theToken) { "Must specify an upload token via -P$tokenProp" }
    }
}

tasks.runIde {
    // Disable auto-reload until we make sure it works correctly for this plugin.
    autoReloadPlugins.set(false)

    // Always enable assertions.
    jvmArgs("-ea")

    // Copy over some JVM args from IntelliJ.
    jvmArgs("-XX:ReservedCodeCacheSize=240m")
    jvmArgs("-XX:+UseConcMarkSweepGC")
    jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=50")
    jvmArgs("-XX:CICompilerCount=2")
    jvmArgs("-Djdk.module.illegalAccess.silent=true")
    jvmArgs("-XX:+UseCompressedOops")

    jvmArgs("-Xms512m")
    jvmArgs("-Xmx2048m")

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

dependencies {
    javaAgent(project(":agent", "runtimeElements"))
    compileOnly(project(":agent")) // 'compileOnly' because it is put on the bootclasspath later.

    implementation("org.ow2.asm:asm:8.0.1")
    implementation("org.ow2.asm:asm-util:8.0.1")
    implementation("org.ow2.asm:asm-commons:8.0.1")

    testImplementation("junit:junit:4.12")
    testImplementation("com.google.truth:truth:1.0.1")
    testImplementation("com.google.truth.extensions:truth-java8-extension:1.0.1")

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
