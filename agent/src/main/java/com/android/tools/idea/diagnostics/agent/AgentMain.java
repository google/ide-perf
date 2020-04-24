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

package com.android.tools.idea.diagnostics.agent;

import java.lang.instrument.Instrumentation;

// Note: this class is accessed reflectively by InstrumentationController.
public class AgentMain {
    public static Instrumentation savedInstrumentationInstance;

    // Called by the JVM when the agent is loaded at startup.
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        agentmain(agentArgs, instrumentation);
    }

    // Called by the JVM when the agent is loaded at runtime.
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        // We just save the Instrumentation instance for later use by the tracer.
        savedInstrumentationInstance = instrumentation;
    }
}
