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

package com.google.idea.perf.sample

/** These are just sample methods that can be traced during integration tests. */
@Suppress("UNUSED_PARAMETER")
object Sample {
    fun a() { b(); d(false) }
    fun b() { c(); c() }
    fun c() { d(true) }
    fun d(recurse: Boolean) { if (recurse) d(false) else e() }
    fun e() {}

    fun paramString(x: String) { paramBool(true) }
    fun paramBool(x: Boolean) { paramByte(1); paramInt(4) }
    fun paramByte(x: Byte) { paramChar('2') }
    fun paramChar(x: Char) { paramShort(3); paramShort(3) }
    fun paramShort(x: Short) {}
    fun paramInt(x: Int) { paramLong(5) }
    fun paramLong(x: Long) { paramFloat(0.0f, 6.0f) }
    fun paramFloat(ignored: Float, x: Float) { paramDouble(0.0, 7.0); paramDouble(0.0, 8.0) }
    fun paramDouble(ignored: Double, x: Double) { if (x < 7.5) paramDouble(0.0, 8.0) }
}
