# Queue Row Immediate Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a normal click on a Playlist/queue row start that entry through the existing server-authoritative `PlayNow` flow while preserving queue removal and drag-and-drop reordering.

**Architecture:** Keep the existing `PlayNowPacket`, client transport, and server `PlaylistManager.handlePlayNow` unchanged. Extend `HorizonRadioScreen`'s existing press/move/release gesture so a release without movement on the original row is a play request, while a moved gesture continues to be validated and sent as a reorder. Capture the pressed `PlaylistEntry` so a playlist synchronization during the gesture cannot change which song a click selects.

**Tech Stack:** Java 8-compatible source, Forge 1.7.10 `GuiScreen`, existing `HorizonRadioClient.ClientTransport`, JUnit 4, Gradle 9.3.1.

## Global Constraints

- Modify only the queue interaction in `HorizonRadioScreen` and its GUI regression tests.
- Do not add a packet, change the network discriminator table, or change server playback semantics.
- A queue-row click is recognized on left-button release, not on press, so drag-and-drop remains usable.
- The `X` remove button, scrollbar, time-bar seeking, tabs, and search/chart result behavior remain unchanged.
- The currently playing row may be clicked to restart through `PlayNow`, but it remains invalid as a reorder source or target.
- Do not optimistically modify the client playlist or now-playing cache; wait for server packets.
- Keep the source Java-8-compatible for the Forge 1.7.10 runtime target.
- Run focused GUI tests and the full Gradle test suite with Java 25 before claiming completion.

---

## File Map

| File | Responsibility |
|---|---|
| `src/main/java/com/horizonradio/client/HorizonRadioScreen.java` | Store the pressed queue entry and dispatch either `PlayNow` or reorder on release. |
| `src/test/java/com/horizonradio/client/GuiLayoutTest.java` | Verify deferred queue clicks, immediate-playback requests, current-row clicks, and unchanged drag behavior. |

No packet, server, model, configuration, resource, or documentation file needs a production change for this feature.

### Task 1: Add failing queue gesture tests

**Files:**

- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java` near the existing direct result-click tests.

**Interfaces:**

- Consumes: the existing `TestScreen`, `RecordingTransport`, and `HorizonRadioScreen` mouse hooks.
- Produces: regression tests that define the release-time click contract before production code changes.

- [ ] **Step 1: Add test helpers for release and held-mouse movement**

Extend the nested `TestScreen` with these package-local test methods:

```java
private void moveHeldMouse(int mouseX, int mouseY) {
    mouseClickMove(mouseX, mouseY, 0, 1L);
}

private void release(int mouseX, int mouseY) {
    mouseMovedOrUp(mouseX, mouseY, 0);
}
```

Keep the existing `click(int, int)` helper unchanged; it represents the left-button press.

- [ ] **Step 2: Add the failing normal-click test**

Add a test with two queue entries and no active playback:

```java
@Test
public void queueRowClickSendsPlayNowOnlyOnRelease() {
    TestScreen screen = new TestScreen();
    screen.setScreenSize(300, 285);
    List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
    entries.add(new HorizonRadioScreen.PlaylistEntry("video", "Song", "2:00", "Alice"));
    entries.add(new HorizonRadioScreen.PlaylistEntry("other", "Other", "3:00", "Bob"));
    screen.updatePlaylist(entries);

    screen.click(50, 62);
    assertNull(transport.playNowRequest);

    screen.release(50, 62);

    assertEquals("video|Song|2:00", transport.playNowRequest);
    assertNull(transport.removedVideoId);
    assertNull(transport.reorderRequest);
}
```

The coordinates target the first queue row when the test screen is 300x285: the queue list begins at y=55 and the row height is 25.

- [ ] **Step 3: Add the failing drag-preservation test**

Add a test proving that movement still sends reorder and does not send `PlayNow`:

```java
@Test
public void queueRowDragStillReordersInsteadOfPlaying() {
    TestScreen screen = new TestScreen();
    screen.setScreenSize(300, 285);
    List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
    entries.add(new HorizonRadioScreen.PlaylistEntry("first", "First", "2:00", "Alice"));
    entries.add(new HorizonRadioScreen.PlaylistEntry("second", "Second", "3:00", "Bob"));
    screen.updatePlaylist(entries);

    screen.click(50, 62);
    screen.moveHeldMouse(50, 87);
    screen.release(50, 87);

    assertEquals("0|1", transport.reorderRequest);
    assertNull(transport.playNowRequest);
}
```

- [ ] **Step 4: Add the current-row click regression test**

Add a test showing that the current row can be clicked but cannot be reordered:

```java
@Test
public void currentQueueRowCanBeClickedButRemainsNonDraggable() {
    TestScreen screen = new TestScreen();
    screen.setScreenSize(300, 285);
    List<HorizonRadioScreen.PlaylistEntry> entries = new ArrayList<HorizonRadioScreen.PlaylistEntry>();
    entries.add(new HorizonRadioScreen.PlaylistEntry("current", "Current", "2:00", "Alice"));
    entries.add(new HorizonRadioScreen.PlaylistEntry("next", "Next", "3:00", "Bob"));
    screen.updatePlaylist(entries);
    screen.updateNowPlaying("Current", 0.5f);

    screen.click(50, 62);
    screen.release(50, 62);

    assertEquals("current|Current|2:00", transport.playNowRequest);
    assertNull(transport.reorderRequest);

    transport.playNowRequest = null;
    screen.click(50, 62);
    screen.moveHeldMouse(50, 87);
    screen.release(50, 87);

    assertNull(transport.playNowRequest);
    assertNull(transport.reorderRequest);
}
```

- [ ] **Step 5: Run the focused tests and confirm the expected RED result**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 \
  GRADLE_USER_HOME=/tmp/horizonradio-gradle \
  ./gradlew test --tests com.horizonradio.client.GuiLayoutTest --no-daemon
```

Expected result before the production change: the new normal-click assertion fails because the existing release path clears the drag state without sending `PlayNow`; the drag test documents the existing passing behavior.

### Task 2: Implement release-time queue click dispatch

**Files:**

- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java` fields around `draggedPlaylistIndex`.
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java` queue handling in `mouseClicked` and `mouseMovedOrUp`.
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java` gesture cleanup in `updatePlaylist` and `onGuiClosed`.

**Interfaces:**

- Consumes: the tests and existing `HorizonRadioClient.sendPlayNow` / `sendReorder` methods.
- Produces: release-time queue click behavior with no network API changes.

- [ ] **Step 1: Add a pending queue-entry field**

Add a field next to `draggedPlaylistIndex`:

```java
private PlaylistEntry draggedPlaylistEntry;
```

This is the immutable client-side nested `HorizonRadioScreen.PlaylistEntry`, not `com.horizonradio.core.model.PlaylistEntry`.

- [ ] **Step 2: Record every non-remove queue-row press**

In the Playlist-tab branch of `mouseClicked`, retain the existing remove-button early return. For any other valid row, always record the gesture, including row zero when it is currently playing:

```java
int playlistIndex = playlistScrollOffset + row;
draggedPlaylistIndex = playlistIndex;
draggedPlaylistEntry = entry;
playlistDragMoved = false;
dragStartMouseX = mouseX;
dragStartMouseY = mouseY;
return;
```

Do not gate gesture recording with `isPlaylistIndexDraggable`; that predicate must continue to gate only reorder targets and sources. This lets a current row click restart playback while keeping current-row dragging disabled.

- [ ] **Step 3: Decide click versus reorder on release**

Replace the existing `shouldSend`-only release block with logic that calculates both outcomes before clearing the gesture state:

```java
int fromIndex = draggedPlaylistIndex;
int targetIndex = playlistIndexAt(mouseX, mouseY);
PlaylistEntry clickedEntry = draggedPlaylistEntry;
boolean shouldPlay = !playlistDragMoved && targetIndex == fromIndex && clickedEntry != null;
boolean shouldSendReorder = playlistDragMoved && isPlaylistIndexDraggable(fromIndex) && targetIndex >= 0
    && targetIndex != fromIndex
    && isPlaylistDropAllowed(targetIndex);

draggedPlaylistIndex = -1;
draggedPlaylistEntry = null;
playlistDragMoved = false;

if (shouldSendReorder) {
    HorizonRadioClient.sendReorder(fromIndex, targetIndex);
} else if (shouldPlay) {
    HorizonRadioClient.sendPlayNow(clickedEntry.videoId, clickedEntry.title, clickedEntry.duration);
}
```

Preserve the existing `state == 0` guard and the surrounding seek/volume-slider handling. A release outside the original row produces neither request. A moved gesture over an invalid target also produces neither request.

- [ ] **Step 4: Clear the captured entry whenever the gesture is discarded**

In `updatePlaylist`, when an out-of-range `draggedPlaylistIndex` clears the gesture, also set `draggedPlaylistEntry = null`. In `onGuiClosed`, clear it alongside `draggedPlaylistIndex` and `playlistDragMoved`. This prevents a later release from reusing a stale entry.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the same focused command from Task 1. Expected result: all `GuiLayoutTest` tests pass, including the new queue click, drag, and current-row cases.

### Task 3: Full verification and handoff

**Files:**

- Verify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Verify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**

- Consumes: the completed implementation and focused test result.
- Produces: evidence that the feature is isolated and the full suite remains green.

- [ ] **Step 1: Run the full Gradle test suite**

Run:

```bash
env JAVA_HOME=/home/justronaut/.jdks/temurin-25.0.4 \
  GRADLE_USER_HOME=/tmp/horizonradio-gradle \
  ./gradlew test --no-daemon
```

Expected result: `BUILD SUCCESSFUL` with zero failed, errored, or skipped tests.

- [ ] **Step 2: Check formatting and the final diff**

Run:

```bash
git diff --check
git diff -- src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git status --short
```

Confirm that production changes are limited to the queue gesture and that no packet, server, or unrelated GUI behavior changed.

- [ ] **Step 3: Commit the implementation when Git identity is available**

Use:

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: play queue rows immediately"
```

The current checkout lacks `user.name`/`user.email`, so this step may require the repository owner to configure Git identity first. Do not rewrite or discard the already staged design Spec while staging the implementation.
