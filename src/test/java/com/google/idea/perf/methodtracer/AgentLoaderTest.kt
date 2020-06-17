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

package com.google.idea.perf.methodtracer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.SystemProperties
import org.junit.Test

class AgentLoaderTest : BasePlatformTestCase() {

    @Test
    fun testLoadAgent() {
        check(SystemProperties.`is`("jdk.attach.allowAttachSelf")) {
            "Must set VM option -Djdk.attach.allowAttachSelf=true to load the agent"
        }
        checkNotNull(AgentLoader.instrumentation) {
            "The instrumentation agent failed to load. See the log for details."
        }
    }
}
