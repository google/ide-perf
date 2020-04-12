package com.android.tools.idea.diagnostics.agent;

/** Handler for method entry/exit events coming from instrumented bytecode. */
public interface MethodListener {
    void enter(int methodId);
    void leave(int methodId);
}
