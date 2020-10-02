[![build status](https://github.com/google/ide-perf/workflows/build/badge.svg)](https://github.com/google/ide-perf/actions?query=branch%3Amaster)
[![plugin version](https://img.shields.io/jetbrains/plugin/v/15104?label=release)](https://plugins.jetbrains.com/plugin/15104-ide-perf)


IDE Perf: Performance diagnostics for [IntelliJ Platform](https://www.jetbrains.com/opensource/idea/)
===
This is a plugin for IntelliJ-based IDEs that gives real-time insight into the performance
of the IDE itself. You can [install](https://plugins.jetbrains.com/plugin/15104-ide-perf)
it from the JetBrains Plugin Repository, then see the [user guide](docs/user-guide.md) for
usage instructions. Note: this is not an officially supported Google product.


Current features
---
* **Method tracing:** get call counts and overhead measurements in real time as the IDE is
  being used. You can trace most methods, including methods in the JDK. The tracing overhead
  is low, so high-traffic methods like `ProgressManager.checkCanceled` can be traced
  without issue. The tracer has both a method list view and a call tree view. Tracing
  commands are auto-completed as you type.

* **Parameter tracing:** track how many times a method is called with a specific argument.
  For example, you can trace the first parameter of `JavaPsiFacade.findClass` to visualize
  class search queries.

* **CachedValue tracing:** track `CachedValue` hit ratios in real time.

See the [user guide](docs/user-guide.md) for instructions on how to use each feature.

Feel free to [file an issue](https://github.com/google/ide-perf/issues) if you find a bug
or have a feature request.

Here is a screenshot of the method tracer:

![screenshot](https://plugins.jetbrains.com/files/15104/screenshot_23378.png)

Comparison to other tools
---
IDE Perf does not replace other tools. A batch profiler such as YourKit is still the first tool
you should reach for when diagnosing an unknown performance issue. However, IDE Perf is useful
when you want to dig deeper into a specific issue.

#### Advantages

* IDE Perf displays data in real time. This is especially useful in user-interactive scenarios
  because you will notice patterns in the way the data changes in response to
  specific user actions.

* Tracing is targeted at only a small set of methods that you care about. This leads to low
  tracing overhead, accurate time measurements, and low visual clutter when looking at
  the call tree. In contrast, the overhead of tracing in batch profilers is often
  prohibitively high.

* IDE Perf is tailored specifically for IntelliJ, which means that it can surface
  IntelliJ-specific diagnostics. The `CachedValue` tracer is a good example of this.

#### Example use cases

* Use the tracer to quickly answer the question, "how fast is the code I just wrote?"
  The tracing data updates in real time, so you can quickly cycle through a variety of user
  scenarios to see if any of them trigger high overhead.

* Use the tracer to figure out _why_ a method has high overhead in a CPU sampling snapshot.
  Is it invoked millions of times? Do particular method arguments lead to
  significantly higher overhead?

* Use wildcard tracing to audit a new feature and look for unexpected overhead.
  For example, if the feature happens to add code that runs on every key press in the
  editor, you will find out about it.

* Use the tracer to accurately measure the effect of a performance optimization.


Contributing
---
See [dev-guide.md](docs/dev-guide.md) for development tips, and see
[CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request. If you plan to make any
large-scale changes, it is a good idea to [file an issue](https://github.com/google/ide-perf/issues)
first to discuss!
