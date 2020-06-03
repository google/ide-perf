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

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.AbstractListModel
import javax.swing.JPanel

private class SuggestionListModel(val data: List<Suggestion>): AbstractListModel<String>() {
    override fun getElementAt(index: Int): String {
        return data[index].name
    }

    override fun getSize(): Int {
        return data.size
    }
}

class AutocompleteView(suggestions: List<Suggestion>): JPanel(BorderLayout()) {
    private val listModel = SuggestionListModel(suggestions)
    private val list = JBList(listModel)
    private val scrollPane = JBScrollPane(
        list,
        JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
    )

    init {
        add(scrollPane, BorderLayout.CENTER)
    }
}
