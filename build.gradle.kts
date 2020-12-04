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
    id("org.jetbrains.intellij").version("0.6.2")
    id("org.jetbrains.kotlin.jvm").version("1.4.0")
}

val isCI = System.getenv("CI") != null
val isRelease = project.findProperty("release") != null
val versionSuffix = if (isRelease) "" else "-SNAPSHOT"

group = "com.google.idea.perf"
version = "1.0.0$versionSuffix"

repositories {
    mavenCentral()
}

// We still compile with JDK 8 for compatibility with Android Studio 4.1.
java.toolchain.languageVersion.set(JavaLanguageVersion.of(8))
val javac = javaToolchains.compilerFor(java.toolchain).get()
val javaHome: Directory = javac.metadata.installationPath
val jdk = javaInstalls.installationForDirectory(javaHome).get().jdk.get()

val kotlinStdlibVersion = "1.3.70" // Should match the version bundled with IDEA.
val kotlinApiVersion = kotlinStdlibVersion.substringBeforeLast('.')

tasks.withType<KotlinCompile> {
    kotlinOptions {
        apiVersion = kotlinApiVersion
        jvmTarget = "1.8"
        kotlinOptions.jdkHome = javaHome.asFile.absolutePath
        allWarningsAsErrors = true
        freeCompilerArgs = listOf("-Xjvm-default=enable")
    }
}

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
    pluginName = "ide-perf"
    version = "2020.2.3"
    downloadSources = !isCI
    updateSinceUntilBuild = false // So that we can leave the until-build blank.
}

tasks.buildSearchableOptions {
    // Disable the (slow) 'buildSearchableOptions' task; we don't need it yet anyway.
    enabled = false
}

tasks.patchPluginXml {
    setSinceBuild("201") // Should be tested occasionally (see runPluginVerifier).
    changeNotes(null) // Should describe changes in the latest release only.
}

tasks.runPluginVerifier {
    ideVersions(
        listOf(
            "201.8743.12", // Should match the since-build from plugin.xml.
            intellij.buildVersion // We check the current version too for deprecations, etc.
        )
    )

    // TODO: VfsStatTreeTable still uses experimental APIs (JBTreeTable).
    val suppressedFailures = listOf(RunPluginVerifierTask.FailureLevel.EXPERIMENTAL_API_USAGES)
    failureLevel(EnumSet.copyOf(RunPluginVerifierTask.FailureLevel.ALL - suppressedFailures))

    // Suppress false-positive NoSuchClassErrors; they are caused by the agent being
    // loaded in the boot classloader rather than the plugin classloader.
    externalPrefixes(listOf("com.google.idea.perf.agent"))

    val verifierHomeProp = "plugin.verifier.home.dir"
    if (System.getProperty(verifierHomeProp) == null) {
        // Download files into the build directory rather than ~/.pluginVerifier.
        System.setProperty(verifierHomeProp, "$buildDir/pluginVerifier")
    }
}

val javaAgent: Configuration by configurations.creating

tasks.withType<PrepareSandboxTask> {
    // Copy agent artifacts into the plugin home directory.
    val agentDir = "$pluginName/agent"
    from(javaAgent) { into(agentDir) }
}

tasks.publishPlugin {
    val tokenProp = "plugins.repository.token"
    token(project.findProperty(tokenProp))
    doFirst {
        check(isRelease) { "Must do a release build when publishing the plugin" }
        check(project.hasProperty(tokenProp)) { "Must specify an upload token via -P$tokenProp" }
    }
}

tasks.runIde {
    // Disable auto-reload until we make sure it works correctly for this plugin.
    autoReloadPlugins = false

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

dependencies {
    javaAgent(project(":agent", "runtimeElements"))
    compileOnly(project(":agent")) // 'compileOnly' because it is put on the bootclasspath later.

    // For JDK 8 we need to explicitly add tools.jar to the classpath.
    compileOnly(jdk.toolsClasspath)
    testRuntimeOnly(jdk.toolsClasspath)

    implementation("org.ow2.asm:asm:8.0.1")
    implementation("org.ow2.asm:asm-util:8.0.1")
    implementation("org.ow2.asm:asm-commons:8.0.1")

    // Add 'compileOnly' dependencies to get sources for certain IDEA dependencies.
    // This is a workaround for https://github.com/JetBrains/gradle-intellij-plugin/issues/264.
    // If you add something here, update the 'checkIdeaDependencyVersions' task as well.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinStdlibVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinStdlibVersion")

    testImplementation("junit:junit:4.12")
    testImplementation("com.google.truth:truth:1.0.1")
    testImplementation("com.google.truth.extensions:truth-java8-extension:1.0.1")

    // For simplicity we assume all 'compileOnly' dependencies should be 'testCompileOnly' as well.
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

/**
 * As a workaround for https://github.com/JetBrains/gradle-intellij-plugin/issues/264
 * we add 'compileOnly' dependencies on certain IDEA dependencies in order to get their sources.
 * This task checks that the versions we use are the same as the ones bundled in IDEA.
 */
val checkIdeaDependencyVersions by tasks.registering {
    doFirst {
        fun findIdeaDependencyVersion(jarPrefix: String): String {
            // We cannot inspect maven coordinates because IDEA bundles dependencies as simple jars.
            for (file in configurations.idea.get()) {
                if (file.name.startsWith("$jarPrefix-")) {
                    val version = file.name.removePrefix("$jarPrefix-").removeSuffix(".jar")
                    if (version.isNotEmpty() && version.first().isDigit()) {
                        return version
                    }
                }
            }
            error("Failed to find the version of $jarPrefix in IDEA dependencies")
        }

        fun checkIdeaDependencyVersion(jarPrefix: String, expectedVersion: String) {
            val ideaVersion = findIdeaDependencyVersion(jarPrefix)
            check(ideaVersion == expectedVersion) {
                "Our version of $jarPrefix ($expectedVersion) " +
                        "does not match the one bundled in IDEA ($ideaVersion)"
            }
        }

        checkIdeaDependencyVersion("kotlin-stdlib", kotlinStdlibVersion)
        checkIdeaDependencyVersion("kotlin-reflect", kotlinStdlibVersion)
    }
}

tasks.check { dependsOn(checkIdeaDependencyVersions) }
