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

package com.google.idea.perf.util

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * This is just a wrapper around [AppExecutorUtil.createBoundedApplicationPoolExecutor]
 * with improved exception logging behavior.
 */
class ExecutorWithExceptionLogging(name: String, maxThreads: Int) {
    private val backend = AppExecutorUtil.createBoundedScheduledExecutorService(name, maxThreads)

    fun execute(task: () -> Unit) {
        backend.execute(addLogging(task))
    }

    fun <T> submit(task: () -> T): Future<T> {
        return backend.submit<T>(addLogging(task))
    }

    fun scheduleWithFixedDelay(task: () -> Unit, initialDelay: Long, delay: Long, unit: TimeUnit) {
        backend.scheduleWithFixedDelay(addLogging(task), initialDelay, delay, unit)
    }

    fun shutdownNow(): List<Runnable> {
        return backend.shutdownNow()
    }

    private fun <T> addLogging(task: () -> T): () -> T {
        return { doWithLogging(task) }
    }

    private fun <T> doWithLogging(task: () -> T): T {
        try {
            return task()
        }
        catch (e: Throwable) {
            if (e !is ControlFlowException) {
                // Log the exception, because ScheduledExecutorService will silently suppresses it!
                Logger.getInstance(ExecutorWithExceptionLogging::class.java).error(e)
            }
            throw e
        }
    }
}
