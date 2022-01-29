/*
 * Copyright 2022 Google LLC
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


import com.google.idea.perf.AgentLoader
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import io.github.classgraph.ClassGraph
import io.github.classgraph.MethodInfo
import org.objectweb.asm.Type
import java.lang.reflect.Modifier

object ClassRegistry {
    private var classpathClasses: List<ClassInfo> = emptyList()

    private fun allLoadedClasses(): List<Class<Any>> {
        val classes = AgentLoader.instrumentation?.allLoadedClasses ?: emptyArray()

        return classes.filter {
            with(it.name) {
                !startsWith("java.lang.invoke") &&
                        !startsWith("com.sun.proxy") &&
                        !startsWith("jdk.internal.reflect")
            }
        }
    }

    fun allClasses(): List<ClassInfo> {
        //full classpath if available
        if (classpathClasses.isNotEmpty()) return classpathClasses

        return allLoadedClasses().mapNotNull { ClassInfo.tryCreate(it) }

    }

    fun scanClassPath() {
        val t0 = System.currentTimeMillis()
        ClassGraph()
            .enableClassInfo()
            .rejectPackages("java.lang.invoke", "com.sun.proxy", "jdk.internal.reflect")
            .ignoreClassVisibility()
            .scan(1)
            .use { scanResult ->
                classpathClasses = scanResult.allClasses.mapNotNull { ClassInfo.tryCreate(it) }
            }
        val scanTime = System.currentTimeMillis() - t0
        val logger = Logger.getInstance(ClassRegistry::class.java)
        logger.info("Classpath scanned in $scanTime ms")

        if (logger.isDebugEnabled) {
            val alreadyLoaded = allLoadedClasses().map { it.name }.toSet()
            val notLoadedSample = classpathClasses
                .filter { !alreadyLoaded.contains(it.name) }
                .take(10)
            Logger.getInstance(ClassRegistry::class.java)
                .debug("Not yet loaded classes sample $notLoadedSample")
        }
    }

    fun affectedClasses(traceRequests: Collection<TraceRequest>): List<ClassInfo> {
        if (traceRequests.isEmpty()) return emptyList()

        if (classpathClasses.isEmpty()) {
            return affectedLoadedClasses(traceRequests).mapNotNull { ClassInfo.tryCreate(it) }
        }

        // full classpath scanning is slow.
        // match classes first, then load full details for that classes and check methods

        val candidates = allClasses()
            .filter { classInfo ->
                traceRequests.any { it.matcher.mightMatchMethodInClass(classInfo.name) }
            }
            .map { it.name }
            .toTypedArray()

        ProgressManager.checkCanceled()

        ClassGraph()
            .acceptClasses(*candidates)
            .enableMethodInfo()
            .ignoreClassVisibility()
            .ignoreFieldVisibility()
            .scan(1).use { scanResult ->
                val result: MutableList<ClassInfo> = mutableListOf()
                for (clazz in scanResult.allClasses) {
                    ProgressManager.checkCanceled()
                    val classDetails = ClassMethods.create(clazz) ?: continue
                    val matches = traceRequests.any {
                        it.matcher.matchesMethodInClass(classDetails)
                    }
                    val details = ClassInfo.tryCreate(clazz)
                    if (matches && details != null) {
                        result.add(details)
                    }
                }
                return result
            }
    }


    // This may be slow if there are many trace requests or if they use broad glob patterns.
    fun affectedLoadedClasses(traceRequests: Collection<TraceRequest>): List<Class<*>> {
        if (traceRequests.isEmpty()) return emptyList()
        val result: MutableList<Class<*>> = mutableListOf()

        for (clazz in allLoadedClasses()) {
            val classDetails = ClassMethods.tryCreate(clazz) ?: continue
            val matches = traceRequests.any {
                it.matcher.matchesMethodInClass(classDetails)
            }
            if (matches) {
                result.add(clazz)
            }
        }
        return result
    }


    fun classDetails(classname: String): ClassMethods? {
        val loadedClassDetails = loadedClassDetails(classname)
        if (loadedClassDetails != null) {
            return loadedClassDetails
        }

        ClassGraph().acceptClasses(classname)
            .enableClassInfo()
            .enableMethodInfo()
            .ignoreClassVisibility()
            .ignoreFieldVisibility()
            .scan(1)
            .use { scanResult ->
                val classInfo = scanResult.getClassInfo(classname) ?: return null
                return ClassMethods.create(classInfo)
            }
    }

    private fun loadedClassDetails(classname: String): ClassMethods? {
        val clazz = allLoadedClasses().firstOrNull { it.name == classname } ?: return null
        return ClassMethods.tryCreate(clazz)
    }
}


class Method internal constructor(
    private val classGraphMethod: MethodInfo?,
    private val javaMethod: java.lang.reflect.Method?,
) {

    val name: String
        get() = javaMethod?.name ?: classGraphMethod?.name ?: ""

    val descriptor: String
        get() {
            if (javaMethod != null) {
                return Type.getMethodDescriptor(javaMethod)
            }
            if (classGraphMethod != null) {
                return classGraphMethod.typeDescriptorStr
            }
            return ""
        }

    override fun toString(): String {
        return name
    }
}

class ClassMethods internal constructor(
    val name: String,
    val declaredMethods: List<Method>,
    val declaredConstructors: List<String>,
) {

    override fun toString(): String {
        return name
    }


    companion object {
        fun create(c: io.github.classgraph.ClassInfo): ClassMethods? {
            try {
                return ClassMethods(
                    name = c.name,
                    declaredMethods = c.declaredMethodInfo.map { Method(it, null) },
                    declaredConstructors = c.declaredConstructorInfo.map { it.typeDescriptorStr },
                )
            } catch (e: Throwable) {
                Logger.getInstance(ClassRegistry::class.java)
                    .warn("Error reading method info for $c", e)
                return null
            }
        }

        fun tryCreate(c: Class<*>): ClassMethods? {
            try {
                return ClassMethods(
                    name = c.name,
                    declaredMethods = c.declaredMethods.map { Method(null, it) },
                    declaredConstructors = c.declaredConstructors.map {
                        Type.getConstructorDescriptor(it)
                    },
                )
            } catch (e: Throwable) {
                Logger.getInstance(ClassRegistry::class.java)
                    .warn("Error reading method info for $c", e)
                return null
            }
        }
    }
}

class ClassInfo private constructor(
    val name: String,
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
    val isThrowable: Boolean,
) {


    companion object {
        fun tryCreate(c: Class<*>): ClassInfo? {
            try {
                return ClassInfo(
                    name = c.name,
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
                    isThrowable = Throwable::class.java.isAssignableFrom(c),
                )
            } catch (e: Throwable) {
                Logger.getInstance(ClassRegistry::class.java)
                    .warn("Error reading class info for $c", e)
                return null
            }
        }

        fun tryCreate(c: io.github.classgraph.ClassInfo): ClassInfo? {
            try {
                return ClassInfo(
                    name = c.name,
                    simpleName = c.simpleName,
                    packageName = c.packageName,
                    isArray = c.isArrayClass,
                    isAnonymousClass = c.isAnonymousInnerClass,
                    isLocalClass = false,
                    isSynthetic = c.isSynthetic,
                    isInterface = c.isInterface,
                    isAbstract = Modifier.isAbstract(c.modifiers),
                    isEnum = c.isEnum,
                    isAnnotation = c.isAnnotation,
                    isThrowable = c.extendsSuperclass(kotlin.Throwable::class.java) || c.extendsSuperclass(
                        java.lang.Throwable::class.java
                    ),
                )
            } catch (e: Throwable) {
                Logger.getInstance(ClassRegistry::class.java)
                    .warn("Error reading class info for $c", e)
                return null
            }
        }
    }

    override fun toString(): String {
        return name
    }
}