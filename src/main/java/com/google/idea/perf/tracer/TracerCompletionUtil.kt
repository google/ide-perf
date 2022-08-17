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

package com.google.idea.perf.tracer

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.PlatformIcons.ABSTRACT_CLASS_ICON
import com.intellij.util.PlatformIcons.ANNOTATION_TYPE_ICON
import com.intellij.util.PlatformIcons.ANONYMOUS_CLASS_ICON
import com.intellij.util.PlatformIcons.CLASS_ICON
import com.intellij.util.PlatformIcons.ENUM_ICON
import com.intellij.util.PlatformIcons.EXCEPTION_CLASS_ICON
import com.intellij.util.PlatformIcons.INTERFACE_ICON
import com.intellij.util.PlatformIcons.METHOD_ICON
import com.intellij.util.PlatformIcons.PACKAGE_ICON
import com.intellij.util.containers.ArrayListSet
import javax.swing.Icon

// Things to improve:
// * Try improving performance when the prefix is empty/small.
// * Consider caching the list of loaded classes if performance becomes a problem.
// * Somehow handle classes that have not been loaded yet.
// * Review how we handle nested classes.
// * Review how we handle two methods having the same name.
// * Consider ordering completion results via PrioritizedLookupElement.

object TracerCompletionUtil {


    /** Creates auto-completion results for all classes and their packages. */
    fun addLookupElementsForLoadedClasses(result: CompletionResultSet) {
        val seenPackages = mutableSetOf<String>()

        val prefixIsEmpty = result.prefixMatcher.prefix.isEmpty()
        var numResultsForEmptyPrefix = 0

        for (classInfo in ClassRegistry.allClasses()) {
            ProgressManager.checkCanceled()

            // Class name completion: com.example.Class
            if (!shouldHideClassFromCompletionResults(classInfo)) {
                result.addElement(createClassLookupElement(classInfo))
                if (prefixIsEmpty && ++numResultsForEmptyPrefix >= 100) {
                    result.restartCompletionOnAnyPrefixChange()
                    break
                }
            }

            // Package wildcard completion: com.example.*
            if (!prefixIsEmpty) {
                val pkgName = classInfo.packageName
                if (pkgName.isNotEmpty() && seenPackages.add(pkgName)) {
                    val lookup = LookupElementBuilder.create("$pkgName.*").withIcon(PACKAGE_ICON)
                    result.addElement(PrioritizedLookupElement.withPriority(lookup, 1.0))
                }
            }
        }
    }

    /** Creates auto-completion results for all methods in the given class. */
    fun addLookupElementsForMethods(className: String, result: CompletionResultSet) {
        val clazz = ClassRegistry.classDetails(className) ?: return

        // Declared methods.
        for (method in clazz.declaredMethods) {
            result.addElement(LookupElementBuilder.create(method.name).withIcon(METHOD_ICON))
        }

        // Constructors.
        if (clazz.declaredConstructors.isNotEmpty()) {
            result.addElement(LookupElementBuilder.create("<init>").withIcon(METHOD_ICON))
        }
    }

    /** A completion item which just inserts a wildcard. */
    object WildcardLookupElement : LookupElement() {

        override fun getLookupString() = "all"

        override fun getAllLookupStrings(): Set<String> = setOf(lookupString, "*")

        override fun handleInsert(context: InsertionContext) {
            context.editor.document.replaceString(context.startOffset, context.tailOffset, "*")
        }

        override fun renderElement(presentation: LookupElementPresentation) {
            presentation.itemText = lookupString
            presentation.icon = AllIcons.Actions.RegexHovered
        }
    }

    /**
     * A prefix matcher that matches *all* strings, unless there is already an exact match.
     * This is useful for making non-matching lookup elements more discoverable.
     */
    class LenientPrefixMatcher(
        private val delegate: PrefixMatcher,
        private val exactPrefixes: Set<String>,
    ) : PrefixMatcher(delegate.prefix) {

        override fun prefixMatches(name: String): Boolean {
            return prefix !in exactPrefixes || delegate.prefixMatches(name)
        }

        override fun isStartMatch(name: String): Boolean = delegate.isStartMatch(name)

        override fun matchingDegree(string: String): Int = delegate.matchingDegree(string)

        override fun cloneWithPrefix(prefix: String): LenientPrefixMatcher {
            return LenientPrefixMatcher(delegate.cloneWithPrefix(prefix), exactPrefixes)
        }
    }

    fun createClassLookupElement(c: ClassInfo): LookupElement {
        val shortName = when {
            c.simpleName.isBlank() -> c.name.substringAfterLast('.') // For anonymous classes.
            else -> c.simpleName
        }
        val contextString = computeClassContextString(c.name, shortName)
        val icon = when {
            c.isInterface -> INTERFACE_ICON
            c.isEnum -> ENUM_ICON
            c.isAnnotation -> ANNOTATION_TYPE_ICON
            c.isThrowable -> EXCEPTION_CLASS_ICON
            c.isAnonymousClass -> ANONYMOUS_CLASS_ICON
            c.isAbstract -> ABSTRACT_CLASS_ICON
            else -> CLASS_ICON
        }
        return ClassLookupElement(c.name, shortName, contextString, icon)
    }

    private fun computeClassContextString(fqName: String, simpleName: String): String {
        return fqName.removeSuffix(simpleName).removeSuffix("$").removeSuffix(".")
    }

    /** An auto-completion result for a class that can be traced. */
    private class ClassLookupElement(
        private val fqName: String,
        private val shortName: String,
        private val contextString: String, // Like package name, but includes enclosing classes.
        private val icon: Icon
    ) : LookupElement() {

        override fun getLookupString(): String = shortName

        override fun getAllLookupStrings(): Set<String> {
            val result = ArrayListSet<String>()
            result.add(lookupString)
            result.add(fqName)
            return result
        }

        override fun handleInsert(context: InsertionContext) {
            // Insert fqName and immediately start completing the method.
            val editor = context.editor
            editor.document.replaceString(context.startOffset, context.tailOffset, fqName)
            EditorModificationUtil.insertStringAtCaret(editor, "#")
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(editor)
        }

        override fun renderElement(presentation: LookupElementPresentation) {
            presentation.itemText = lookupString
            presentation.icon = icon
            if (contextString.isNotEmpty()) {
                presentation.tailText = " (${contextString})"
            }
        }
    }

    private fun shouldHideClassFromCompletionResults(c: ClassInfo): Boolean {
        return c.isArray ||
                c.isAnonymousClass ||
                c.isLocalClass ||
                c.isSynthetic ||
                c.simpleName.isBlank() ||
                c.name.startsWith("java.lang.invoke.") ||
                c.name.startsWith("com.sun.proxy.") ||
                c.name.startsWith("jdk.internal.reflect.") ||
                c.name.contains("$$")
    }
}
