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

plugins {
    id("java")
}

group = "com.google.idea.perf"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.jar {
    archiveFileName.set("agent.jar")
    manifest.attributes(
        "Manifest-Version" to "1.0",
        "Premain-Class" to "com.google.idea.perf.agent.AgentMain",
        "Agent-Class" to "com.google.idea.perf.agent.AgentMain",
        "Can-Retransform-Classes" to "true",
        "Implementation-Title" to "com.google.idea.perf.agent",
        "Boot-Class-Path" to archiveFileName
    )
}
