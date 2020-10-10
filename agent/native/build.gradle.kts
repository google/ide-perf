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

import org.gradle.internal.jvm.Jvm

// Note: java-library and cpp-library cannot be applied simultaneously
// (see https://github.com/gradle/gradle-native/issues/352), which is why
// we use two separate build.gradle files for the agent.
plugins {
    id("cpp-library")
}

group = "com.google.idea.perf"

library {
    baseName.set("agent")

    binaries.configureEach {
        val compileTask = compileTask.get()

        // JNI and JVMTI headers.
        val javaHome = Jvm.current().javaHome
        val osFamily = targetPlatform.targetMachine.operatingSystemFamily
        val osDirName = when {
            osFamily.isLinux -> "linux"
            osFamily.isMacOs -> "darwin"
            osFamily.isWindows -> "win32"
            else -> error("Unknown OS: $osFamily")
        }
        compileTask.includes.from(
            "$javaHome/include",
            "$javaHome/include/$osDirName"
        )

        // Compiler args.
        val compilerArgs = when (toolChain) {
            is GccCompatibleToolChain -> {
                arrayOf("-std=c++17", "-Wall", "-Wpedantic", "-Werror")
            }
            is VisualCpp -> {
                arrayOf("/std:c++17")
            }
            else -> error("Unknown toolchain: $toolChain")
        }
        compileTask.compilerArgs.addAll(*compilerArgs)
    }
}
