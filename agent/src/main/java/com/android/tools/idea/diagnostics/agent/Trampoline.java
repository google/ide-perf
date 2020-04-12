package com.android.tools.idea.diagnostics.agent;

/**
 * We cannot emit bytecode which calls directly into the tracer, because the tracer is loaded
 * by the system classloader, whereas some of the bytecode we instrument may have been
 * loaded by the boot classloader.
 *
 * So, we install a layer of indirection between these two worlds.
 */
public class Trampoline {
    public static MethodListener methodListener; // Set by the tracer before any methods are instrumented.

    // These methods are called from instrumented bytecode.
    public static void enter(int methodId) { methodListener.enter(methodId); }
    public static void leave(int methodId) { methodListener.leave(methodId); }
}
