# Task 3 Report — Playlist Discovery Tab

Status: DONE

Changed files:

- `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
  - Split the old four-tab layout into five tabs: Charts, Search, Queue, Playlists, Radio.
  - Kept `isPlaylistTab()` mapped to the Queue tab for existing test compatibility.
  - Added `isPlaylistDiscoveryTab()`, `playlistUrlField`, playlist-import loading/reveal state, imported-results rendering, imported-results bulk/single queue actions, and separate imported-results scroll handling.
  - Preserved queue-only drag/reorder/remove behavior on the Queue tab.
  - Routed playlist import through `HorizonRadioClient.sendPlaylistImport(...)` and kept import completion from mutating the queue.

- `src/test/java/com/horizonradio/client/GuiLayoutTest.java`
  - Added RED/GREEN GUI coverage for Queue vs Playlists tab separation.
  - Added GUI coverage for playlist imported-row add-to-queue, direct play, and bulk add ordering.
  - Updated progress/transport assertions for the new fifth-tab layout and compact queue-selection transport path.

RED phase:

Command:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
```

Observed output:

```text
> Task :compileTestJava FAILED
/home/justronaut/IdeaProjects/HorizonRadio/src/test/java/com/horizonradio/client/GuiLayoutTest.java:322: error: cannot find symbol
        assertEquals("playlist-song", screen.getPlaylistResultsSnapshot().get(0).videoId);
                                            ^
  symbol:   method getPlaylistResultsSnapshot()

/home/justronaut/IdeaProjects/HorizonRadio/src/test/java/com/horizonradio/client/GuiLayoutTest.java:1409: error: cannot find symbol
            return super.isPlaylistDiscoveryTab();
                        ^
  symbol: method isPlaylistDiscoveryTab()

2 errors

FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':compileTestJava'.
```

That matched the expected missing Playlists-tab/field behavior: the new GUI contract did not exist yet.

GREEN phase:

Command:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest
```

Observed output:

```text
> Task :test

BUILD SUCCESSFUL in 5s
13 actionable tasks: 2 executed, 11 up-to-date
```

Focused verification from the brief:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
```

Observed output:

```text
> Task :test

BUILD SUCCESSFUL in 4s
13 actionable tasks: 1 executed, 12 up-to-date
```

Self-review:

- The imported playlist list is now local UI state only; importing does not add anything to the queue.
- Only explicit single-row `+/-`, bulk button, or direct-play interactions trigger the Task 2 queue handoff / play-now behavior.
- Queue behavior remains isolated: reorder, remove, and queue-row click-on-release still live only on the Queue tab.
- Search, Charts, and Radio kept their existing shared field behavior.
- `isPlaylistTab()` compatibility was preserved while introducing the explicit Playlists discovery mode.

Concerns:

- To fit five tabs in the existing 300px panel, tab buttons are narrower than before. The focused automated tests pass, but an in-game visual smoke check would still be worthwhile for label fit, especially for “Playlists”.

---

## Fix Round 1 — reviewer findings addressed

Findings addressed:

1. Ran the required full `./gradlew build`, captured the initial failure, fixed it, and reran to green.
2. Added a real `GuiLayoutTest` that proves playlist discovery results published through the client cache are copied into a newly opened screen while the queue stays separate.
3. Removed the redundant `onPlaylistScreenClosed(this)` call from `HorizonRadioScreen.onGuiClosed()` and added a focused regression that starts a real pending playlist import, closes the active importing screen, and verifies stale completion invalidation still happens with a single generation bump.

Fix-round TDD / verification log:

Command:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest.playlistDiscoveryResultsCachePopulateANewScreenWithoutChangingQueue --tests com.horizonradio.client.GuiLayoutTest.closingAnActiveScreenInvalidatesPlaylistImportsOnce
```

Output:

```text
> Configure project :
You might want to check out './gradlew :faq' if your build fails.
Build script update from 2.0.20 to 2.0.29 available! Run ./gradlew updateBuildScript

> Task :downloadFernflower SKIPPED
> Task :downloadVanillaJars SKIPPED
> Task :generateForgeSrgMappings SKIPPED
> Task :extractDependencyATs SKIPPED
> Task :processInjectedInterfacesResources NO-SOURCE
> Task :injectTags UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :extractNatives2 UP-TO-DATE
> Task :processInjectedTagsResources NO-SOURCE
> Task :processApiResources NO-SOURCE
> Task :compileInjectedInterfacesJava NO-SOURCE
> Task :processMcLauncherResources NO-SOURCE
> Task :injectedInterfacesClasses UP-TO-DATE
> Task :createMcLauncherFiles UP-TO-DATE
> Task :compileInjectedTagsJava UP-TO-DATE
> Task :injectedTagsClasses UP-TO-DATE
> Task :compileMcLauncherJava UP-TO-DATE
> Task :mcLauncherClasses UP-TO-DATE
> Task :processResources
> Task :mergeVanillaSidedJars SKIPPED
> Task :deobfuscateMergedJarToSrg SKIPPED
> Task :decompileSrgJar SKIPPED
> Task :cleanupDecompSrgJar SKIPPED
> Task :patchDecompiledJar SKIPPED
> Task :applyJST SKIPPED
> Task :remapDecompiledJar SKIPPED
> Task :decompressDecompiledSources UP-TO-DATE
> Task :processPatchedMcResources UP-TO-DATE
> Task :compilePatchedMcJava UP-TO-DATE
> Task :patchedMcClasses UP-TO-DATE
> Task :compileApiJava NO-SOURCE
> Task :apiClasses UP-TO-DATE
> Task :compileJava UP-TO-DATE
> Task :classes

> Task :compileTestJava
Jabel: initialized
Note: /home/justronaut/IdeaProjects/HorizonRadio/src/test/java/com/horizonradio/client/GuiLayoutTest.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: /home/justronaut/IdeaProjects/HorizonRadio/src/test/java/com/horizonradio/client/GuiLayoutTest.java uses unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

> Task :testClasses

> Task :test

GuiLayoutTest > closingAnActiveScreenInvalidatesPlaylistImportsOnce FAILED
    java.lang.AssertionError at GuiLayoutTest.java:130

2 tests completed, 1 failed

> Task :test FAILED

[Incubating] Problems report is available at: file:///home/justronaut/IdeaProjects/HorizonRadio/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test'.
> There were failing tests. See the report at: file:///home/justronaut/IdeaProjects/HorizonRadio/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights from a Build Scan (powered by Develocity).

BUILD FAILED in 5s
13 actionable tasks: 3 executed, 10 up-to-date
Configuration cache entry stored.
```

That RED result showed the focused close-hook regression was wrong at first because it did not put the screen into an actual in-flight playlist import state. I tightened the test to start a real pending import before closing the screen.

Command:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest.playlistDiscoveryResultsCachePopulateANewScreenWithoutChangingQueue --tests com.horizonradio.client.GuiLayoutTest.closingAnActivePlaylistImportScreenInvalidatesPlaylistImportsOnce
```

Output:

```text
> Configure project :
You might want to check out './gradlew :faq' if your build fails.
Build script update from 2.0.20 to 2.0.29 available! Run ./gradlew updateBuildScript

> Task :extractDependencyATs SKIPPED
> Task :downloadVanillaJars SKIPPED
> Task :downloadFernflower SKIPPED
> Task :generateForgeSrgMappings SKIPPED
> Task :processApiResources NO-SOURCE
> Task :compileInjectedInterfacesJava NO-SOURCE
> Task :processInjectedInterfacesResources NO-SOURCE
> Task :injectedInterfacesClasses UP-TO-DATE
> Task :injectTags UP-TO-DATE
> Task :processMcLauncherResources NO-SOURCE
> Task :extractNatives2 UP-TO-DATE
> Task :processInjectedTagsResources NO-SOURCE
> Task :processResources UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :createMcLauncherFiles UP-TO-DATE
> Task :compileInjectedTagsJava UP-TO-DATE
> Task :injectedTagsClasses UP-TO-DATE
> Task :compileMcLauncherJava UP-TO-DATE
> Task :mcLauncherClasses UP-TO-DATE
> Task :mergeVanillaSidedJars SKIPPED
> Task :deobfuscateMergedJarToSrg SKIPPED
> Task :decompileSrgJar SKIPPED
> Task :cleanupDecompSrgJar SKIPPED
> Task :patchDecompiledJar SKIPPED
> Task :applyJST SKIPPED
> Task :remapDecompiledJar SKIPPED
> Task :decompressDecompiledSources UP-TO-DATE
> Task :processPatchedMcResources UP-TO-DATE
> Task :compilePatchedMcJava UP-TO-DATE
> Task :patchedMcClasses UP-TO-DATE
> Task :compileApiJava NO-SOURCE
> Task :apiClasses UP-TO-DATE
> Task :compileJava UP-TO-DATE
> Task :classes UP-TO-DATE

> Task :compileTestJava
Jabel: initialized
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

> Task :testClasses
> Task :test

[Incubating] Problems report is available at: file:///home/justronaut/IdeaProjects/HorizonRadio/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 7s
13 actionable tasks: 2 executed, 11 up-to-date
Configuration cache entry stored.
```

Command:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
```

Output:

```text
> Task :extractDependencyATs SKIPPED
> Task :downloadVanillaJars SKIPPED
> Task :compileInjectedInterfacesJava NO-SOURCE
> Task :downloadFernflower SKIPPED
> Task :generateForgeSrgMappings SKIPPED
> Task :injectTags UP-TO-DATE
> Task :processInjectedTagsResources NO-SOURCE
> Task :extractNatives2 UP-TO-DATE
> Task :processMcLauncherResources NO-SOURCE
> Task :processApiResources NO-SOURCE
> Task :createMcLauncherFiles UP-TO-DATE
> Task :processInjectedInterfacesResources NO-SOURCE
> Task :injectedInterfacesClasses UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :compileInjectedTagsJava UP-TO-DATE
> Task :injectedTagsClasses UP-TO-DATE
> Task :compileMcLauncherJava UP-TO-DATE
> Task :mcLauncherClasses UP-TO-DATE
> Task :mergeVanillaSidedJars SKIPPED
> Task :deobfuscateMergedJarToSrg SKIPPED
> Task :decompileSrgJar SKIPPED
> Task :cleanupDecompSrgJar SKIPPED
> Task :patchDecompiledJar SKIPPED
> Task :applyJST SKIPPED
> Task :remapDecompiledJar SKIPPED
> Task :decompressDecompiledSources UP-TO-DATE
> Task :processPatchedMcResources UP-TO-DATE
> Task :compilePatchedMcJava UP-TO-DATE
> Task :patchedMcClasses UP-TO-DATE
> Task :compileApiJava NO-SOURCE
> Task :apiClasses UP-TO-DATE

> Task :compileJava
Jabel: initialized
Note: /home/justronaut/IdeaProjects/HorizonRadio/src/main/java/com/horizonradio/client/HorizonRadioClient.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

> Task :classes
> Task :compileTestJava UP-TO-DATE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 6s
13 actionable tasks: 1 executed, 12 up-to-date
Configuration cache entry stored.
```

Command:

```bash
./gradlew build
```

Initial output:

```text
> Task :extractDependencyATs SKIPPED
> Task :generateForgeSrgMappings SKIPPED
> Task :downloadFernflower SKIPPED
> Task :downloadVanillaJars SKIPPED
> Task :processInjectedInterfacesResources NO-SOURCE
> Task :compileInjectedInterfacesJava NO-SOURCE
> Task :processInjectedTagsResources NO-SOURCE
> Task :processMcLauncherResources NO-SOURCE
> Task :processApiResources NO-SOURCE
> Task :injectedInterfacesClasses UP-TO-DATE
> Task :checkstyleInjectedInterfaces NO-SOURCE
> Task :injectTags UP-TO-DATE
> Task :spotlessInternalRegisterDependencies UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :extractNatives2 UP-TO-DATE
> Task :createMcLauncherFiles UP-TO-DATE
> Task :mergeVanillaSidedJars SKIPPED
> Task :spotlessKotlin UP-TO-DATE
> Task :deobfuscateMergedJarToSrg SKIPPED
> Task :spotlessScala UP-TO-DATE
> Task :spotlessMisc UP-TO-DATE
> Task :compileInjectedTagsJava UP-TO-DATE
> Task :injectedTagsClasses UP-TO-DATE
> Task :checkstyleInjectedTags SKIPPED
> Task :compileMcLauncherJava UP-TO-DATE
> Task :mcLauncherClasses UP-TO-DATE
> Task :checkstyleMcLauncher SKIPPED
> Task :decompileSrgJar SKIPPED
> Task :spotlessKotlinCheck UP-TO-DATE
> Task :spotlessMiscCheck UP-TO-DATE
> Task :spotlessScalaCheck UP-TO-DATE
> Task :packageMcLauncher UP-TO-DATE
> Task :cleanupDecompSrgJar SKIPPED
> Task :patchDecompiledJar SKIPPED
> Task :applyJST SKIPPED
> Task :remapDecompiledJar SKIPPED
> Task :sourcesJar
> Task :decompressDecompiledSources UP-TO-DATE
> Task :processPatchedMcResources UP-TO-DATE
> Task :compilePatchedMcJava UP-TO-DATE
> Task :patchedMcClasses UP-TO-DATE
> Task :checkstylePatchedMc SKIPPED
> Task :compileApiJava NO-SOURCE
> Task :apiClasses UP-TO-DATE
> Task :packagePatchedMc UP-TO-DATE
> Task :processIdeVirtualMainResources SKIPPED
> Task :checkstyleApi NO-SOURCE
> Task :compileJava UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileIdeVirtualMainJava SKIPPED
> Task :compileTestJava UP-TO-DATE
> Task :testClasses UP-TO-DATE
> Task :jar
> Task :spotlessJava
> Task :spotlessJavaCheck FAILED
> Task :reobfJar
> Task :checkstyleTest
> Task :checkstyleMain

> Task :test

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':spotlessJavaCheck'.
> The following files had format violations:
      src/main/java/com/horizonradio/client/HorizonRadioClient.java
          @@ -1771,7 +1771,8 @@
           ········return·converted;
           ····}
           
          -····private·static·void·publishPlaylistResults(List<HorizonRadioScreen.SearchResult>·results,·HorizonRadioScreen·screen)·{
          +····private·static·void·publishPlaylistResults(List<HorizonRadioScreen.SearchResult>·results,
          +········HorizonRadioScreen·screen)·{
           ········CACHED_PLAYLIST_RESULTS.clear();
           ········if·(results·!=·null)·{
           ············CACHED_PLAYLIST_RESULTS.addAll(results);
          @@ -1934,7 +1935,11 @@
           ········································updateCachedResultDuration(resolution.videoId,·resolution.metadata);
           ····································}·else·if·(resolution·!=·null·&&·resolution.videoId·!=·null)·{
           ········································failedIds.add(resolution.videoId);
          -········································debugChat(addItemFailureMessage(origin,·resolution.videoId,·failureMessage(null,·resolution)));
          +········································debugChat(
          +············································addItemFailureMessage(
          +················································origin,
          +················································resolution.videoId,
          +················································failureMessage(null,·resolution)));
           ····································}
           ································}
           ································clearPendingAdds(origin,·failedIds);
          @@ -2015,18 +2020,12 @@
           
           ····private·static·String·addItemFailureMessage(QueueSelectionOrigin·origin,·String·videoId,·String·message)·{
           ········return·(origin·==·QueueSelectionOrigin.PLAYLIST·?·"Playlist·konnte·nicht·hinzugefügt·werden:·"
          -············:·"Chart·konnte·nicht·hinzugefügt·werden:·")
          -············+·videoId
          -············+·"·("
          -············+·message
          -············+·")";
          +············:·"Chart·konnte·nicht·hinzugefügt·werden:·")·+·videoId·+·"·("·+·message·+·")";
           ····}
           
           ····private·static·String·addSuccessMessage(QueueSelectionOrigin·origin,·int·count)·{
           ········return·(origin·==·QueueSelectionOrigin.PLAYLIST·?·"Playlist-Auswahl·lokal·aufgelöst:·"
          -············:·"Chart-Auswahl·lokal·aufgelöst:·")
          -············+·count
          -············+·"·Titel.";
          +············:·"Chart-Auswahl·lokal·aufgelöst:·")·+·count·+·"·Titel.";
           ····}
           
           ····private·static·boolean·isValidChartDuration(String·videoId,·long·durationMs)·{
      src/main/java/com/horizonradio/client/HorizonRadioScreen.java
          @@ -772,23 +772,17 @@
           
           ····@Override
           ····protected·void·keyTyped(char·typedChar,·int·keyCode)·{
      ... (66 more lines that didn't fit)
  Violations also present in:
      src/test/java/com/horizonradio/client/GuiLayoutTest.java
      src/test/java/com/horizonradio/client/HorizonRadioClientDiscoveryTest.java
  Run './gradlew spotlessApply' to fix all violations.

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 29s
29 actionable tasks: 8 executed, 21 up-to-date
Configuration cache entry stored.
```

Command:

```bash
./gradlew spotlessApply
```

Output:

```text
> Configure project :
You might want to check out './gradlew :faq' if your build fails.
Build script update from 2.0.20 to 2.0.29 available! Run ./gradlew updateBuildScript
Recommend replacing '4.19.0' with '4.19' for Eclipse JDT
'indentWithSpaces' is deprecated, use 'leadingTabsToSpaces' in your gradle build script instead.
'indentWithSpaces' is deprecated, use 'leadingTabsToSpaces' in your gradle build script instead.

> Task :spotlessInternalRegisterDependencies UP-TO-DATE
> Task :spotlessMisc UP-TO-DATE
> Task :spotlessKotlin UP-TO-DATE
> Task :spotlessMiscApply UP-TO-DATE
> Task :spotlessKotlinApply UP-TO-DATE
> Task :spotlessScala UP-TO-DATE
> Task :spotlessJava UP-TO-DATE
> Task :spotlessScalaApply UP-TO-DATE
> Task :spotlessJavaApply
> Task :spotlessApply

BUILD SUCCESSFUL in 1s
9 actionable tasks: 1 executed, 8 up-to-date
Configuration cache entry stored.
```

Command:

```bash
./gradlew test --tests com.horizonradio.client.GuiLayoutTest --tests com.horizonradio.client.HorizonRadioClientDiscoveryTest
```

Post-format output:

```text
> Task :extractDependencyATs SKIPPED
> Task :downloadVanillaJars SKIPPED
> Task :compileInjectedInterfacesJava NO-SOURCE
> Task :downloadFernflower SKIPPED
> Task :generateForgeSrgMappings SKIPPED
> Task :injectTags UP-TO-DATE
> Task :processInjectedTagsResources NO-SOURCE
> Task :extractNatives2 UP-TO-DATE
> Task :processMcLauncherResources NO-SOURCE
> Task :processApiResources NO-SOURCE
> Task :createMcLauncherFiles UP-TO-DATE
> Task :processInjectedInterfacesResources NO-SOURCE
> Task :injectedInterfacesClasses UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :compileInjectedTagsJava UP-TO-DATE
> Task :injectedTagsClasses UP-TO-DATE
> Task :compileMcLauncherJava UP-TO-DATE
> Task :mcLauncherClasses UP-TO-DATE
> Task :mergeVanillaSidedJars SKIPPED
> Task :deobfuscateMergedJarToSrg SKIPPED
> Task :decompileSrgJar SKIPPED
> Task :cleanupDecompSrgJar SKIPPED
> Task :patchDecompiledJar SKIPPED
> Task :applyJST SKIPPED
> Task :remapDecompiledJar SKIPPED
> Task :decompressDecompiledSources UP-TO-DATE
> Task :processPatchedMcResources UP-TO-DATE
> Task :compilePatchedMcJava UP-TO-DATE
> Task :patchedMcClasses UP-TO-DATE
> Task :compileApiJava NO-SOURCE
> Task :apiClasses UP-TO-DATE

> Task :compileJava
Jabel: initialized
Note: /home/justronaut/IdeaProjects/HorizonRadio/src/main/java/com/horizonradio/client/HorizonRadioClient.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

> Task :classes
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

> Task :compileTestJava
Jabel: initialized

> Task :testClasses
> Task :test

[Incubating] Problems report is available at: file:///home/justronaut/IdeaProjects/HorizonRadio/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 8s
13 actionable tasks: 3 executed, 10 up-to-date
Configuration cache entry stored.
```

Command:

```bash
./gradlew build
```

Final output:

```text
> Task :downloadFernflower SKIPPED
> Task :generateForgeSrgMappings SKIPPED
> Task :downloadVanillaJars SKIPPED
> Task :extractDependencyATs SKIPPED
> Task :processApiResources NO-SOURCE
> Task :processMcLauncherResources NO-SOURCE
> Task :processInjectedInterfacesResources NO-SOURCE
> Task :processInjectedTagsResources NO-SOURCE
> Task :compileInjectedInterfacesJava NO-SOURCE
> Task :injectedInterfacesClasses UP-TO-DATE
> Task :checkstyleInjectedInterfaces NO-SOURCE
> Task :injectTags UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :extractNatives2 UP-TO-DATE
> Task :spotlessInternalRegisterDependencies UP-TO-DATE
> Task :createMcLauncherFiles UP-TO-DATE
> Task :compileInjectedTagsJava UP-TO-DATE
> Task :spotlessMisc UP-TO-DATE
> Task :injectedTagsClasses UP-TO-DATE
> Task :spotlessKotlin UP-TO-DATE
> Task :spotlessScala UP-TO-DATE
> Task :checkstyleInjectedTags SKIPPED
> Task :spotlessMiscCheck UP-TO-DATE
> Task :spotlessScalaCheck UP-TO-DATE
> Task :mergeVanillaSidedJars SKIPPED
> Task :spotlessKotlinCheck UP-TO-DATE
> Task :compileMcLauncherJava UP-TO-DATE
> Task :mcLauncherClasses UP-TO-DATE
> Task :checkstyleMcLauncher SKIPPED
> Task :packageMcLauncher UP-TO-DATE
> Task :deobfuscateMergedJarToSrg SKIPPED
> Task :decompileSrgJar SKIPPED
> Task :cleanupDecompSrgJar SKIPPED
> Task :patchDecompiledJar SKIPPED
> Task :applyJST SKIPPED
> Task :remapDecompiledJar SKIPPED
> Task :sourcesJar
> Task :decompressDecompiledSources UP-TO-DATE
> Task :processPatchedMcResources UP-TO-DATE
> Task :compilePatchedMcJava UP-TO-DATE
> Task :patchedMcClasses UP-TO-DATE
> Task :checkstylePatchedMc SKIPPED
> Task :compileApiJava NO-SOURCE
> Task :apiClasses UP-TO-DATE
> Task :packagePatchedMc UP-TO-DATE
> Task :processIdeVirtualMainResources SKIPPED
> Task :checkstyleApi NO-SOURCE
> Task :compileJava UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileIdeVirtualMainJava SKIPPED
> Task :compileTestJava UP-TO-DATE
> Task :testClasses UP-TO-DATE
> Task :jar
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck
> Task :reobfJar
> Task :assemble
> Task :ideVirtualMainClasses SKIPPED
> Task :checkstyleIdeVirtualMain SKIPPED
> Task :checkstyleTest
> Task :checkstyleMain
> Task :test
> Task :check
> Task :build

BUILD SUCCESSFUL in 25s
29 actionable tasks: 8 executed, 21 up-to-date
Configuration cache entry stored.
```
