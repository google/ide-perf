package com.android.tools.diagnostics.agent;

import java.lang.instrument.Instrumentation;

public class Premain {
    public static Instrumentation savedInstrumentationInstance;

    /**
     * This is called by the JVM at startup.
     * We just save the Instrumentation instance for later use by the tracer.
     */
    public static void premain(String args, Instrumentation instrumentation) {
        savedInstrumentationInstance = instrumentation;
    }
}
