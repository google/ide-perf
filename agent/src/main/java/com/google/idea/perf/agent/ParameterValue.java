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

import java.util.Objects;

public final class ParameterValue {
    private final Object value;
    private final byte index;

    public ParameterValue(Object value, byte index) {
        this.value = value;
        this.index = index;
    }

    public Object getValue() {
        return value;
    }

    public byte getIndex() {
        return index;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ParameterValue that = (ParameterValue) o;
        return index == that.index && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, index);
    }
}
