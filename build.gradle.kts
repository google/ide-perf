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

import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL

plugins {
    id("java")
    id("org.jetbrains.intellij").version("0.4.18")
    id("org.jetbrains.kotlin.jvm").version("1.4.0")
}

group = "com.google.idea.perf"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val kotlinStdlibVersion = "1.3.70" // Should match the version bundled with IDEA.
val kotlinApiVersion = kotlinStdlibVersion.substringBeforeLast('.')

configureEach(tasks.compileKotlin, tasks.compileTestKotlin) {
    kotlinOptions {
        apiVersion = kotlinApiVersion
        jvmTarget = "11"
        freeCompilerArgs = listOf("-Xjvm-default=enable")
    }
}

val isCI = System.getenv("CI") != null

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
    pluginName = "ide-perf"
    version = "2020.2"
    setPlugins("java") // Used for the PsiElementFinder demo.
    downloadSources = !isCI
}

tasks.buildSearchableOptions {
    // Disable the (slow) 'buildSearchableOptions' task; we don't need it yet anyway.
    enabled = false
}

tasks.patchPluginXml {
    setSinceBuild("19.3") // TODO: Test on earlier versions.
    setUntilBuild("202.*")
}

configureEach(tasks.prepareSandbox, tasks.prepareTestingSandbox) {
    // Copy the agent jar into our plugin home directory.
    from(tasks.getByPath(":agent:jar")) {
        into(intellij.pluginName)
    }
}

tasks.runIde {
    // Disable auto-reload until we make sure it works correctly for this plugin.
    systemProperty("idea.auto.reload.plugins", "false")

    // Always enable assertions.
    jvmArgs("-ea")

    // Copy over some JVM args from IntelliJ.
    jvmArgs("-XX:ReservedCodeCacheSize=240m")
    jvmArgs("-XX:+UseConcMarkSweepGC")
    jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=50")
    jvmArgs("-Djdk.module.illegalAccess.silent=true")
    jvmArgs("-XX:+UseCompressedOops")

    enableAgent()
}

tasks.test {
    testLogging.exceptionFormat = FULL
    testLogging.showStandardStreams = isCI
    enableAgent()
}

fun JavaForkOptions.enableAgent() {
    val atStartup = findProperty("loadAgentAtStartup") == "true"
    if (atStartup) {
        // Add the -javaagent startup flag.
        val agentName = tasks.getByPath(":agent:jar").outputs.files.singleFile.name
        val agentPath = "${intellij.sandboxDirectory}/plugins/${intellij.pluginName}/$agentName"
        jvmArgs("-javaagent:$agentPath")
    } else {
        // Let the agent load itself later.
        systemProperty("jdk.attach.allowAttachSelf", true)
    }
}

dependencies {
    // Using 'compileOnly' because the agent is loaded in the boot classpath.
    compileOnly(project(":agent"))
    testCompileOnly(project(":agent"))

    implementation("org.ow2.asm:asm:8.0.1")
    implementation("org.ow2.asm:asm-util:8.0.1")
    implementation("org.ow2.asm:asm-commons:8.0.1")

    // Add 'compileOnly' dependencies to get sources for certain IDEA dependencies.
    // This is a workaround for https://github.com/JetBrains/gradle-intellij-plugin/issues/264.
    // If you add something here, update the 'checkIdeaDependencyVersions' task as well.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinStdlibVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinStdlibVersion")
    testCompileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinStdlibVersion")
    testCompileOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinStdlibVersion")

    testImplementation("junit:junit:4.12")
    testImplementation("com.google.truth:truth:1.0.1")
    testImplementation("com.google.truth.extensions:truth-java8-extension:1.0.1")
}

/**
 * As a workaround for https://github.com/JetBrains/gradle-intellij-plugin/issues/264
 * we add 'compileOnly' dependencies on certain IDEA dependencies in order to get their sources.
 * This task checks that the versions we use are the same as the ones bundled in IDEA.
 */
val checkIdeaDependencyVersions = tasks.register("checkIdeaDependencyVersions") {
    doFirst {
        fun findIdeaDependencyVersion(jarPrefix: String): String {
            // We cannot inspect maven coordinates because IDEA bundles dependencies as simple jars.
            for (file in configurations.idea.get().files) {
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

fun <T : Task> configureEach(vararg taskProviders: TaskProvider<T>, action: T.() -> Unit) {
    for (taskProvider in taskProviders) {
        taskProvider(action)
    }
}
