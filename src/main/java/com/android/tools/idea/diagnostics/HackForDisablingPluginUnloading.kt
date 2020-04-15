package com.android.tools.idea.diagnostics

/**
 * This plugin does not yet support dynamic unloading, mainly because it uses an instrumentation agent
 * on the boot classpath. IDEA does not seem to have a mechanism for marking plugins ineligible for unloading,
 * so instead we register a fake component to implicitly disable unloading.
 *
 * See https://github.com/JetBrains/gradle-intellij-plugin/issues/476.
 */
class HackForDisablingPluginUnloading
