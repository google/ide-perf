User guide for `ide-perf`
===

<!-- TODO: Add images? -->

Basic tracing
---

The tracer window can be opened via `Help -> Diagnostic Tools -> Tracer`.
The text field at the top of the window is the tracer command line.
Use the `trace` command to trace specific methods.

#### Examples
* `trace com.intellij.openapi.progress.ProgressManager#checkCanceled`
* `trace com.intellij.openapi.progress.ProgressManager#*`
* `trace com.intellij.openapi.progress.*`

You generally do not need to type out the fully qualified name of a class. Instead, type
the short name and select the fully qualified name from the completion popup.

Once you trace some methods you will start to see call counts and time measurements
in real time as you use the IDE. The `List` view shows aggregate statistics for each tracepoint.
The `Tree` view shows the full call tree. To see additional details for a tracepoint, double-click
or press `enter` on a selected row in the `List` view.

#### Caveats
* Only concrete methods can be traced. Tracing abstract methods has no effect.
* Class name completion only works for classes which have already been loaded by the JVM.

#### Other commands
* `clear` - Clear the current call tree data
* `reset` - Untrace all methods and clear the call tree
* `trace psi-finders` - Trace all overrides of `PsiElementFinder.findClass`
* `trace tracer` - Trace important methods in the tracer itself
* `save /path/to/file.png` - Save a screenshot of the current tracer window

Parameter tracing
---
With parameter tracing you can track the arguments passed to a given method. The tracer
calls `toString` on the arguments and uses the result to create synthetic tracepoints.

To trace a parameter, pass its index inside square brackets at the end of a trace command.
You can trace multiple parameters at once by separating their indices with commas.

#### Examples
* `trace com.intellij.psi.impl.JavaPsiFacadeImpl#findClass[0]`

Tip: if you can change the IDE source code, then parameter tracing provides a versatile way to
visualize any event you like. Just declare a temporary method with a single string parameter;
invoke that method with the runtime values you want to track; and then trace that method's
parameter with the tracer.

Specialized tracers
---
There are a couple specialized tracers which track specific subsystems of IntelliJ. These are
slightly more experimental than the main method tracer.

* The CachedValue tracer can be opened via `Help -> Diagnostic Tools -> CachedValue Tracer`.
  It provides hit/miss ratios for the various `CachedValue` caches in the IDE.

* The VFS tracer can be opened via `Help -> Diagnostic Tools -> VFS Tracer`.
  It provides statistics on PSI file parses and PSI stub accesses. These statistics are viewable
  in a flat list view or in a file tree view. Make sure to use the `start` command to
  enable VFS data collection.

<!-- TODO: Further explain these tracers and their subcommands. -->

FAQ
---

### Which methods can be traced?

Essentially all methods can be traced, including methods in the JDK.

Technically there are a few methods which cause `StackOverflowException` if they are traced,
due to their use in the tracer instrumentation hook. This includes a couple `ThreadLocal` methods
and a few methods in the tracer itself.

### How high is the tracing overhead?
The tracing overhead is _approximately_ 200 ns per traced method call, assuming sufficient
JIT optimization. This number varies across platforms; you can run the microbenchmark at
`TracerIntegrationTest.testTracingOverhead` if you want to estimate tracing overhead for
your local machine. Tracepoint data is stored in thread-local data structures, so you should
not have to worry about contention between threads. The tracing overhead is unlikely to be
improved because much of the time is spent calling `System.nanoTime`.
You may want to read about some
[performance quirks of `System.nanoTime`](https://shipilev.net/blog/2014/nanotrusting-nanotime/),
especially if you are running on Windows.

We do not have an estimate for parameter tracing overhead, in part because parameter
tracing involves calling `toString` on arbitrary user objects.

Note that the tracer UI adds some overhead too on the UI thread. The overhead scales
with the number of tracepoints displayed in the tracer window.

### Why does it take a while to trace an entire package?

The JVM takes around 100 ms to retransform a loaded class after its bytecode has been
modified by the tracer. If you are tracing many classes, this can add up.

One workaround is to trace the package _before_ most of its classes are loaded. For example, you
can issue tracing commands while still at the IDE welcome screen (before opening any projects).
