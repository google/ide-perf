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

package com.google.idea.perf.methodtracer

import com.google.idea.perf.AgentLoader
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.debugger.engine.JVMNameUtil.CONSTRUCTOR_NAME
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.PlatformIcons.ABSTRACT_CLASS_ICON
import com.intellij.util.PlatformIcons.ABSTRACT_METHOD_ICON
import com.intellij.util.PlatformIcons.ANNOTATION_TYPE_ICON
import com.intellij.util.PlatformIcons.ANONYMOUS_CLASS_ICON
import com.intellij.util.PlatformIcons.CLASS_ICON
import com.intellij.util.PlatformIcons.ENUM_ICON
import com.intellij.util.PlatformIcons.EXCEPTION_CLASS_ICON
import com.intellij.util.PlatformIcons.INTERFACE_ICON
import com.intellij.util.PlatformIcons.METHOD_ICON
import com.intellij.util.PlatformIcons.PACKAGE_ICON
import com.intellij.util.containers.ArrayListSet
import java.lang.reflect.Modifier
import javax.swing.Icon

// Things to improve:
// * Try improving performance when the prefix is empty/small.
// * Consider caching the list of loaded classes if performance becomes a problem.
// * Somehow handle classes that have not been loaded yet.
// * Review how we handle nested classes.
// * Review how we handle two methods having the same name.
// * Consider ordering completion results via PrioritizedLookupElement.

object ClassCompletionUtil {

    /** Creates auto-completion results for all loaded classes and their packages. */
    fun addLookupElementsForLoadedClasses(result: MutableList<LookupElement>) {
        val instrumentation = AgentLoader.instrumentation ?: return
        val seenPackages = mutableSetOf<String>()

        for (clazz in instrumentation.allLoadedClasses) {
            ProgressManager.checkCanceled()
            val classInfo = ClassInfo.tryCreate(clazz) ?: continue

            // Class name completion: com.example.Class
            if (!shouldHideClassFromCompletionResults(classInfo)) {
                result.add(createClassLookupElement(classInfo))
            }

            // Package wildcard completion: com.example.*
            val packageName = classInfo.packageName
            if (packageName.isNotEmpty() && seenPackages.add(packageName)) {
                val lookup = LookupElementBuilder.create("$packageName.*").withIcon(PACKAGE_ICON)
                result.add(PrioritizedLookupElement.withPriority(lookup, 1.0))
            }
        }
    }

    /** Creates auto-completion results for all methods in the given class. */
    fun addLookupElementsForMethods(className: String, result: MutableList<LookupElement>) {
        val instrumentation = AgentLoader.instrumentation ?: return
        val allClasses = instrumentation.allLoadedClasses
        val clazz = allClasses.firstOrNull { it.name == className } ?: return

        // Declared methods.
        for (method in clazz.declaredMethods) {
            val icon = when {
                Modifier.isAbstract(method.modifiers) -> ABSTRACT_METHOD_ICON
                else -> METHOD_ICON
            }
            result.add(LookupElementBuilder.create(method.name).withIcon(icon))
        }

        // Constructors.
        if (clazz.constructors.isNotEmpty()) {
            result.add(LookupElementBuilder.create(CONSTRUCTOR_NAME).withIcon(METHOD_ICON))
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

    // Prefer the overload accepting ClassInfo to get better icons.
    fun createClassLookupElement(fqName: String): LookupElement {
        val simpleName = fqName.substringAfterLast('.').substringAfterLast('$')
        val contextString = computeClassContextString(fqName, simpleName)
        return ClassLookupElement(fqName, simpleName, contextString, CLASS_ICON)
    }

    private fun createClassLookupElement(c: ClassInfo): LookupElement {
        val contextString = computeClassContextString(c.fqName, c.simpleName)
        val icon = when {
            c.isInterface -> INTERFACE_ICON
            c.isEnum -> ENUM_ICON
            c.isAnnotation -> ANNOTATION_TYPE_ICON
            c.isThrowable -> EXCEPTION_CLASS_ICON
            c.isAnonymousClass -> ANONYMOUS_CLASS_ICON
            c.isAbstract -> ABSTRACT_CLASS_ICON
            else -> CLASS_ICON
        }
        return ClassLookupElement(c.fqName, c.simpleName, contextString, icon)
    }

    private fun computeClassContextString(fqName: String, simpleName: String): String {
        return fqName.removeSuffix(simpleName).removeSuffix("$").removeSuffix(".")
    }

    /** An auto-completion result for a class that can be traced. */
    private class ClassLookupElement(
        private val fqName: String,
        private val simpleName: String,
        private val contextString: String, // Like package name, but includes enclosing classes.
        private val icon: Icon
    ) : LookupElement() {

        override fun getLookupString(): String = simpleName

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
            AutoPopupController.getInstance(context.project).autoPopupMemberLookup(editor, null)
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
                c.fqName.startsWith("java.lang.invoke.") ||
                c.fqName.startsWith("com.sun.proxy.") ||
                c.fqName.startsWith("jdk.internal.reflect.") ||
                c.fqName.contains("$$")
    }

    // Interacting with arbitrary user classes is dangerous, because exceptions like
    // NoClassDefFoundError may be thrown in certain corner cases. So we compute any info
    // we need upfront and fail gracefully if exceptions are thrown.
    private class ClassInfo private constructor(
        val fqName: String,
        val simpleName: String,
        val packageName: String,
        val isArray: Boolean,
        val isAnonymousClass: Boolean,
        val isLocalClass: Boolean,
        val isSynthetic: Boolean,
        val isInterface: Boolean,
        val isAbstract: Boolean,
        val isEnum: Boolean,
        val isAnnotation: Boolean,
        val isThrowable: Boolean
    ) {
        companion object {
            fun tryCreate(c: Class<*>): ClassInfo? {
                try {
                    return ClassInfo(
                        fqName = c.name,
                        simpleName = c.simpleName,
                        packageName = c.packageName,
                        isArray = c.isArray,
                        isAnonymousClass = c.isAnonymousClass,
                        isLocalClass = c.isLocalClass,
                        isSynthetic = c.isSynthetic,
                        isInterface = c.isInterface,
                        isAbstract = Modifier.isAbstract(c.modifiers),
                        isEnum = c.isEnum,
                        isAnnotation = c.isAnnotation,
                        isThrowable = Throwable::class.java.isAssignableFrom(c)
                    )
                } catch (ignored: Throwable) {
                    return null
                }
            }
        }
    }
}
