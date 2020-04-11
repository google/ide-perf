package com.android.tools.idea.diagnostics.agent;

/**
 * We cannot emit bytecode which calls directly into the tracer, because the tracer is loaded
 * by the system classloader, and we may want to instrument code loaded by the boot classloader.
 *
 * So, we install a layer of indirection between these two worlds.
 */
public class Trampoline {
    public static MethodListener methodListener; // Set by the tracer before any methods are instrumented.

    @SuppressWarnings("unused") // Called from instrumented byte code.
    public static void enter(int id) {
        methodListener.enter(id);
    }

    @SuppressWarnings("unused") // Called from instrumented byte code.
    public static void leave(int id) {
        methodListener.leave(id);
    }
}
