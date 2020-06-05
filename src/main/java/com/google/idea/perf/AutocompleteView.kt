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

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.AbstractListModel
import javax.swing.JList
import javax.swing.ListCellRenderer

private class SuggestionListModel(val data: List<Suggestion>): AbstractListModel<Suggestion>() {
    override fun getElementAt(index: Int): Suggestion = data[index]

    override fun getSize(): Int = data.size
}

private class AutocompleteCellRenderer:
    JBPanel<AutocompleteCellRenderer>(BorderLayout()),
    ListCellRenderer<Suggestion>
{
    companion object {
        val BACKGROUND_COLOR = JBColor {
            EditorColorsUtil.getGlobalOrDefaultColor(EditorColors.DOCUMENTATION_COLOR)
                ?: JBColor.PanelBackground
        }

        val SELECTED_BACKGROUND_COLOR = JBColor(0xC5DFFC, 0x113A5C)
    }

    private val label = JBLabel()

    init {
        add(label, BorderLayout.WEST)
    }

    override fun getListCellRendererComponent(
        list: JList<out Suggestion>?,
        value: Suggestion?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        background = if (isSelected) SELECTED_BACKGROUND_COLOR else BACKGROUND_COLOR
        label.text = "<HTML>" + value?.name
            ?.replace(MatchResult.MATCHED_RANGE_OPEN_TOKEN, "<B>")
            ?.replace(MatchResult.MATCHED_RANGE_CLOSE_TOKEN, "</B>") + "</HTML>"
        return this
    }
}

class AutocompleteView(suggestions: List<Suggestion>): JBPanel<AutocompleteView>(BorderLayout()) {
    private val listModel = SuggestionListModel(suggestions)
    private val list = JBList(listModel)
    private val scrollPane = JBScrollPane(
        list,
        JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
    )

    init {
        background = AutocompleteCellRenderer.BACKGROUND_COLOR
        list.cellRenderer = AutocompleteCellRenderer()
        add(scrollPane, BorderLayout.CENTER)
    }
}
