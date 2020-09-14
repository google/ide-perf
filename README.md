[![build status](https://github.com/google/ide-perf/workflows/build/badge.svg)](https://github.com/google/ide-perf/actions?query=branch%3Amaster)

Performance diagnostics for [IntelliJ Platform](https://www.jetbrains.com/opensource/idea/)
===
This is a plugin for IntelliJ-based IDEs that gives real-time insight into the performance
of the IDE itself. The plugin is under active development and should be considered experimental.
This is not an officially supported Google product.

So far the plugin has the ability to trace methods on demand and get call counts and time
measurements in real time as the IDE is being used. Since the tracer is tightly integrated with
IntelliJ, it can easily do things like "trace all extensions that implement the PsiElementFinder
extension point". Click [here](http://services.google.com/fh/files/misc/trace-psi-finders.png)
to see what that looks like. The overhead of the tracer is low, so high-traffic methods like
`HashMap.get()` and `ProgressManager.checkCanceled()` can be traced without issue.

Getting started
---
`ide-perf` is not yet released on the plugin repository, so you will need to build and install
it manually. You can expect `ide-perf` to be compatible with the latest stable version of IntelliJ.

To build the plugin, make sure you have `JAVA_HOME` pointing to JDK 11 or higher, then run

```bash
./gradlew assemble
```

Next add these JVM flags to the IDE that you would like to trace.

* `-Djdk.attach.allowAttachSelf=true`

  This flag ensures that the tracer can instrument IDE bytecode.
  It is already set by default if you are using a recent version of IntelliJ.

* `-Dplugin.path=/path/to/ide-perf/build/idea-sandbox/plugins/ide-perf`

  This flag loads the `ide-perf` plugin.
  Be sure to adjust the `/path/to/ide-perf` part based on where you cloned this repository.
  Alternatively, if you do not want to add this flag, you can run `Install Plugin from Disk`
  in the IDE and choose the zip file under `build/distributions`.

Next launch the IDE and open the tracer via `Help -> Diagnostic Tools -> Tracer`. In the window
that pops up you can try typing in a command like this:
```
trace com.intellij.openapi.progress.ProgressManager#checkCanceled
```

See [user-guide.md](docs/user-guide.md) for the full guide on using `ide-perf`.

Contributing
---
To make changes, open this project in IntelliJ and import it as a Gradle project. Please
see [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request.
