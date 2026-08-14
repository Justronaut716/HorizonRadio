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
