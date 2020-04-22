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
