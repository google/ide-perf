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

package com.google.idea.perf.agent;

/**
 * We cannot emit bytecode which calls directly into the tracer, because the tracer is loaded
 * by the system classloader, whereas some of the bytecode we instrument may have been
 * loaded by the boot classloader.
 *
 * So, we install a layer of indirection between these two worlds.
 */
public class Trampoline {
    public static MethodListener methodListener; // Set by the tracer.

    // These methods are called from instrumented bytecode.
    public static void enter(int methodId, ParameterValue[] args) {
        methodListener.enter(methodId, args);
    }

    public static void leave(int methodId) { methodListener.leave(methodId); }
}
