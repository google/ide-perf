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

package com.google.idea.perf.vfstracer;

/**
 * Hook methods that IntelliJ can call into as a link between IntelliJ's core class loaders and
 * IDE Perf's plugin class loader.
 */
public final class VfsTracerTrampoline {
    private static VfsTracerHook hook = new VfsTracerHookStub();

    public static void installHook(VfsTracerHook newHook) {
        hook = newHook;
    }

    public static void onPsiElementCreate(Object psiElement) {
        hook.onPsiElementCreate(psiElement);
    }

    public static Object onStubIndexProcessorCreate(Object processor) {
        return hook.onStubIndexProcessorCreate(processor);
    }

    private static class VfsTracerHookStub implements VfsTracerHook {
        @Override
        public void onPsiElementCreate(Object psiElement) {}

        @Override
        public Object onStubIndexProcessorCreate(Object processor) {
            return processor;
        }
    }
}
