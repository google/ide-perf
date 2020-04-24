A performance diagnostic plugin for the [IntelliJ Platform](https://www.jetbrains.com/opensource/idea/)
===
This is a plugin for developers working on the IDE itself to get real-time insight into
the performance of their code. It is developed by the Android Studio team at Google.

This project is under active development and should be considered experimental.

So far the plugin has the ability to trace methods on demand and get call counts and timing
measurements in real time as the IDE is being used. Since the tracer is tightly integrated with
IntelliJ, it can easily do things like "trace all extensions that implement the PsiElementFinder
extension point." The overhead of the tracer is low, so high-traffic methods like `HashMap.get()`
and `ProgressManager.checkCanceled()` can be traced without issue.

Getting started
---
To run a development instance of IntelliJ with this plugin installed, run
```bash
./gradlew runIde
```
Then open the tracer via `Help -> Diagnostic Tools -> Tracer`. In the window that pops up
you can try typing in a command like this:
```
trace com.intellij.openapi.progress.ProgressManager#checkCanceled
```
Or this:
```
trace psi finders
```
The tracer view should show call counts and overhead measurements in real time as you use the IDE.

Using in production
---
To use the plugin in a production IDE, run `./gradlew assemble` and look for a zip file
in the `build/distributions` directory. You can install the plugin from that zip file via
the `Install Plugin from Disk` action in IntelliJ. In order for the bytecode instrumentation agent
to load correctly, you will need to do at least **one** of the following:

- Add `-Djdk.attach.allowAttachSelf=true` to your VM options. This is already done by default
  in recent versions of IntelliJ, but you should make sure you don't have custom VM options
  overriding the defaults.

- Alternatively, add a VM option of the form `-javaagent:/path/to/this-plugin/agent.jar`
  to attach the instrumentation agent at startup.

Contributing
---
To make changes, open this project in IntelliJ and import it as a Gradle project. Please
see [CONTRIBUTING.md](CONTRIBUTING.md) before uploading a pull request.
