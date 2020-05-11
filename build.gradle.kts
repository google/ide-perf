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
    id("org.jetbrains.kotlin.jvm").version("1.3.71")
}

group = "com.google.idea.perf"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

configureEach(tasks.compileKotlin, tasks.compileTestKotlin) {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xjvm-default=enable")
    }
}

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
    pluginName = "ide-perf"
    version = "2020.1"
    setPlugins("java") // Used for the PsiElementFinder demo.
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
    // TODO: This does not work. See com.google.idea.perf.HackForDisablingPluginUnloading.
    systemProperty("idea.auto.reload.plugins", "false")

    // Always enable assertions.
    jvmArgs("-ea")

    // Copy over some JVM args from IntelliJ.
    jvmArgs("-XX:ReservedCodeCacheSize=240m")
    jvmArgs("-XX:+UseConcMarkSweepGC")
    jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=50")
    jvmArgs("-Djdk.module.illegalAccess.silent=true")
    jvmArgs("-XX:+UseCompressedOops")

    if (findProperty("loadAgentAtStartup") == "true") {
        // Add the -javaagent startup flag.
        val agentName = tasks.getByPath(":agent:jar").outputs.files.singleFile.name
        val agentPath = "${intellij.sandboxDirectory}/plugins/${intellij.pluginName}/$agentName"
        jvmArgs("-javaagent:$agentPath")
    } else {
        // Let the agent load itself later.
        systemProperty("jdk.attach.allowAttachSelf", true)
    }
}

tasks.test {
    testLogging.exceptionFormat = FULL
    systemProperty("jdk.attach.allowAttachSelf", true)
}

dependencies {
    // Using 'compileOnly' because the agent is loaded in the boot classpath.
    compileOnly(project(":agent"))

    implementation("org.ow2.asm:asm:8.0.1")
    implementation("org.ow2.asm:asm-commons:8.0.1") {
        exclude(group = "org.ow2.asm", module = "asm-tree")
        exclude(group = "org.ow2.asm", module = "asm-analysis")
    }

    // TODO: Find a way to attach the Kotlin stdlib sources without declaring a 'compileOnly' dependency.
    //  See https://github.com/JetBrains/gradle-intellij-plugin/issues/264.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compileOnly("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("junit:junit:4.12")
}

fun <T : Task> configureEach(vararg taskProviders: TaskProvider<T>, action: T.() -> Unit) {
    for (taskProvider in taskProviders) {
        taskProvider(action)
    }
}
