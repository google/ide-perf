package com.android.tools.diagnostics.agent;

/** Handler for method entry/exit events coming from instrumented bytecode. */
public interface MethodListener {

    /** Entering a method. */
    void enter(int methodId);

    /** Leaving a method, either normally or because of a thrown exception. */
    void leave(int methodId);
}
