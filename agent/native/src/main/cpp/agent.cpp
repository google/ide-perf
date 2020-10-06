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

#include <jni.h>
#include <jvmti.h>
#include <stdio.h>
#include <algorithm>

// This JVMTI agent gathers performance data for use in IDE Perf.
//
// JVMTI docs: https://docs.oracle.com/en/java/javase/11/docs/specs/jvmti.html
// JNI docs: https://docs.oracle.com/en/java/javase/11/docs/specs/jni/index.html

static bool
HandleJvmtiError(jvmtiEnv* jvmti, jvmtiError err, const char* msg) {
    if (err == JVMTI_ERROR_NONE) {
        return false;
    } else {
        char* name = nullptr;
        jvmti->GetErrorName(err, &name);
        auto desc = name != nullptr ? name : "Unknown";
        fprintf(stderr, "JVMTI error: %d(%s) %s\n", err, desc, msg);
        jvmti->Deallocate((unsigned char*)name);
        return true;
    }
}

constexpr jint kHeapSamplingInterval = 256 * 1024;
constexpr jlong kHeapSamplingIntervalLong = kHeapSamplingInterval;

// A thread-local estimate of heap allocations in bytes.
//
// TODO: The use of thread_local linkage here assumes that Java threads have a
// 1-to-1 correspondence with native threads. That assumption will be invalid
// after Project Loom. One alternative is to use jvmti->GetThreadLocalStorage,
// but unfortunately the overhead is somewhat high. Maybe a better alternative
// is to switch to Java-land in the SampledObjectAlloc callback and just
// store the counter in a normal ThreadLocal variable.
static thread_local jlong thread_allocations = 0;

extern "C" void JNICALL
SampledObjectAlloc(
    jvmtiEnv* jvmti,
    JNIEnv* jni,
    jthread thread,
    jobject object,
    jclass klass,
    jlong size
) {
    // Note: in the case where the allocation overlaps with the boundary between
    // two heap sampling intervals, JVMTI does not specify whether the
    // "excess bytes" affect the length of the next sampling interval.
    // So, we have to live with the possibility that those excess bytes are
    // lost and that we slightly underestimate allocations.
    thread_allocations += std::max(kHeapSamplingIntervalLong, size);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_google_idea_perf_AllocationSampling_countAllocationsForCurrentThread(
    JNIEnv* jni,
    jobject thiz
) {
    return thread_allocations;
}

// This function is called by the JVM when the agent is loaded after startup.
extern "C" JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
    jvmtiError err;

    jvmtiEnv* jvmti;
    if (vm->GetEnv((void**)&jvmti, JVMTI_VERSION_11) != JNI_OK) {
        fprintf(stderr, "Error retrieving JVMTI function table\n");
        return JNI_ERR;
    }

    // Set JVMTI capabilities.
    jvmtiCapabilities capabilities = {};
    capabilities.can_generate_sampled_object_alloc_events = 1;
    err = jvmti->AddCapabilities(&capabilities);
    if (HandleJvmtiError(jvmti, err, "Failed to add JVMTI capabilities")) {
        return JNI_ERR;
    }

    // Set JVMTI callbacks.
    jvmtiEventCallbacks callbacks = {};
    callbacks.SampledObjectAlloc = SampledObjectAlloc;
    err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (HandleJvmtiError(jvmti, err, "Failed to set JVMTI callbacks")) {
        return JNI_ERR;
    }

    // Set heap sampling interval.
    err = jvmti->SetHeapSamplingInterval(kHeapSamplingInterval);
    if (HandleJvmtiError(jvmti, err, "Failed to set heap sampling interval")) {
        return JNI_ERR;
    }

    // Enable JVMTI events.
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                          JVMTI_EVENT_SAMPLED_OBJECT_ALLOC,
                                          /*thread*/ nullptr);
    if (HandleJvmtiError(jvmti, err, "Failed to enable JVMTI events")) {
        return JNI_ERR;
    }

    return JNI_OK;
}

// This function is called by the JVM when the agent is loaded at startup.
extern "C" JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    return Agent_OnAttach(vm, options, reserved);
}
