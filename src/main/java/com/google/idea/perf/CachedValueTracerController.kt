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

package com.google.idea.perf

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit.MILLISECONDS

class CachedValueTracerController: Disposable {
    companion object {
        private val LOG = Logger.getInstance(CachedValueTracerController::class.java)
        private const val REFRESH_DELAY_MS = 30L
    }

    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("CachedValue Tracer", 1)

    override fun dispose() {
        executor.shutdownNow()
    }

    fun startDataRefreshLoop() {
        executor.scheduleWithFixedDelay(this::dataRefreshLoop, 0L, REFRESH_DELAY_MS, MILLISECONDS)
    }

    private fun dataRefreshLoop() {
    }
}
