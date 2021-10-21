[![build status](https://github.com/google/ide-perf/workflows/build/badge.svg)](https://github.com/google/ide-perf/actions?query=branch%3Amaster)
[![plugin version](https://img.shields.io/jetbrains/plugin/v/15104?label=release)](https://plugins.jetbrains.com/plugin/15104-ide-perf)


IDE Perf: Performance diagnostics for [IntelliJ Platform](https://www.jetbrains.com/opensource/idea/)
===
This is a plugin for IntelliJ-based IDEs that gives real-time insight into the performance of the
IDE itself. You can [install](https://plugins.jetbrains.com/plugin/15104-ide-perf) it from the
JetBrains Plugin Repository, then see the [user guide](docs/user-guide.md) for usage instructions.
Feel free to [file an issue](https://github.com/google/ide-perf/issues) if you find a bug or have
a feature request. Note: this is not an officially supported Google product.


Features
---
* **Method tracer:** choose some methods, then get call counts and overhead measurements in real
  time as the IDE is being used. Methods are traced on-the-fly using bytecode instrumentation.
  There is both a method list view and a call tree view. Tracing commands are auto-completed as
  you type. Tracing overhead is low (approx. 200 ns per call).

* **Parameter tracer:** track how many times a method is called with a specific argument.
  For example, you can trace the first parameter of `JavaPsiFacade.findClass` to visualize
  class search queries.

* **CachedValue tracer:** track `CachedValue` hit ratios in real time.

Here is a screenshot of the method tracer:

![screenshot](https://plugins.jetbrains.com/files/15104/screenshot_23378.png)


Comparison to other tools
---
A batch profiler such as YourKit is still the first tool you should reach for when
diagnosing an unknown performance issue. However, IDE Perf is useful when you want
to dig deeper into a specific issue.

#### Advantages

* IDE Perf displays data in real time. This is useful in user-interactive scenarios
  because you will notice patterns in the way the data changes in response to
  specific user actions.

* Tracing is targeted at only a small set of methods that you care about. This leads to low
  tracing overhead, accurate time measurements, and low visual clutter when looking at
  the call tree.

* IDE Perf can surface IntelliJ-specific diagnostics such as `CachedValue` statistics.

#### Example use cases

* Use the tracer to get call counts when CPU sampling is insufficient.

* Use the tracer to accurately measure the effect of a performance optimization.

* Use wildcard tracing to audit a new feature and look for unexpected overhead.

* Given a user-reported performance bug, search for repro scenarios by tracing suspect methods
  and then monitoring the tracing data while trying out a variety of user interactions.


Contributing
---
See [dev-guide.md](docs/dev-guide.md) for development tips, and see
[CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request. If you plan to make any
large-scale changes, it is a good idea to [start a discussion](https://github.com/google/ide-perf/discussions)
first! If you need ideas on what to implement, check out the "future work" discussion
[here](https://github.com/google/ide-perf/discussions/41).
