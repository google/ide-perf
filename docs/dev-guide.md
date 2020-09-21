Developer guide for ide-perf
===

Steps to publish a new version of the plugin
---
* Make sure `README.md` and `docs/user-guide.md` are up to date.

* Prepare `build.gradle.kts` for the release.

    * Bump the plugin version number.

    * Review the platform compatibility metadata (`setSinceBuild` and `setUntilBuild`).
      Make sure tests pass for each IntelliJ version in the specified range.

    * Set the `changeNotes` field to a description of what changed since the last release.

* Create a release build by running
  ```
  ./gradlew -Prelease clean build
  ```
  The `-Prelease` flag ensures that the "SNAPSHOT" suffix is removed from the plugin version.
  The release build will end up under `build/distributions` as a zip file.

* Test the release build in IntelliJ using the `Install Plugin From Disk` action.

* Publish the release build to the JetBrains Plugins Repository by running
  ```
  ./gradlew -Prelease -Pplugins.repository.token=<token> publishPlugin
  ```
  where `<token>` is the ide-perf upload token.
  The release will await approval from JetBrains before it becomes publicly available.

* Tag the current commit with an annotated git tag. For example:
  ```
  git tag v1.0.0 -m 'Releasing version 1.0.0'
  ```
  Then push to the remote with
  ```
  git push --follow-tags
  ```

* Optional: [create a release](https://github.com/google/ide-perf/releases/new) on GitHub too.
