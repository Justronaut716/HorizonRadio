# WebPrototype Minecraft UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Minecraft HorizonRadio screen so it matches the WebPrototype's Songs/Radio panel and interactions while omitting the prototype's upper Client/Server/Group controls.

**Architecture:** Keep `HorizonRadioScreen` as the Forge 1.7.10 immediate-mode screen and keep `HorizonRadioClient` plus all packet and media paths unchanged. Add a focused geometry helper for reference coordinates, render the two-column panel with existing Minecraft primitives and icon textures, and transform input through the same scale used for rendering.

**Tech Stack:** Java, Forge 1.7.10 `GuiScreen`/`GuiTextField`/`GuiButton`, LWJGL 2 `GL11`/`Mouse`, Gradle GTNH convention build, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-06-webprototype-minecraft-ui-design.md`

## Global Constraints

- Do not add or change server packets, server queue authority, media resolution, or unrelated gameplay behavior.
- Do not render or implement the WebPrototype's upper `Client`, `Server`, or `Group` controls.
- Preserve existing client-side Search, Charts, Playlists, Radio, Favorites, Queue, direct-play, seek, volume, and drag-reorder behavior.
- Use Minecraft-native rendering and input; do not copy HTML, CSS, or JavaScript into production code.
- Match the WebPrototype palette: `#202020`, `#242424`, `#272727`, `#292929`, `#315b38`, `#79d38a`, and `#a8d7ab` equivalents.
- Verify each focused change with the targeted Gradle test and finish with `./gradlew test` and `./gradlew build`.

---

### Task 1: Add the reference geometry contract and logo asset

**Files:**
- Create: `src/main/java/com/horizonradio/client/HorizonRadioUiLayout.java`
- Test: `src/test/java/com/horizonradio/client/HorizonRadioUiLayoutTest.java`
- Create: `src/main/resources/assets/horizonradio/textures/gui/HorizonRadioLogo.png`

**Interfaces:**
- Produces `HorizonRadioUiLayout.create(int screenWidth, int screenHeight)`.
- Produces `panelWidth()`, `panelHeight()`, `scale()`, `panelLeft()`, `panelTop()`, `bodyTop()`, `bodyBottom()`, `contentLeft()`, `contentWidth()`, `queueLeft()`, `queueWidth()`, and `toLogicalMouseX/Y(int)` for `HorizonRadioScreen`.
- The reference panel is `360x322` logical pixels before fitting; the panel is centered and uniformly scaled down when the available screen is smaller.

- [ ] **Step 1: Write the failing geometry test**

```java
@Test
public void prototypePanelUsesTheReferenceDimensions() {
    assertEquals(360, HorizonRadioScreen.PANEL_WIDTH);
    assertEquals(322, HorizonRadioScreen.PANEL_HEIGHT);
}
```

- [ ] **Step 2: Run the focused test to verify the expected failure**

Run: `./gradlew test --tests com.horizonradio.client.HorizonRadioUiLayoutTest`

Expected: the assertions fail because the current screen still exposes the old `300x285` panel.

- [ ] **Step 3: Implement the minimal immutable layout helper and update the test with its contract**

Use integer reference coordinates, compute
`scale = min(1, (screenWidth - 20) / 360, (screenHeight - 20) / 322)`, center the panel, and invert that scale in the two mouse conversion methods. Keep all panel column and footer coordinates in this class so rendering and hit-testing use one source of truth.

- [ ] **Step 4: Copy and trim the Prototype logo into the resource tree**

Use the existing transparent PNG from Project2, trim only transparent margins, and keep the result grayscale with alpha. Do not copy the WebPrototype background image or any HTML-only asset.

- [ ] **Step 5: Run the focused test and inspect the asset**

Run: `./gradlew test --tests com.horizonradio.client.HorizonRadioUiLayoutTest`

Expected: all geometry assertions pass and the PNG is present under the HorizonRadio resource namespace.

- [ ] **Step 6: Commit the geometry and asset task**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioUiLayout.java src/test/java/com/horizonradio/client/HorizonRadioUiLayoutTest.java src/main/resources/assets/horizonradio/textures/gui/HorizonRadioLogo.png
git commit -m "feat: add responsive HorizonRadio panel geometry"
```

### Task 2: Replace the old tab/header wiring with the Prototype navigation

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Test: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- Existing `HorizonRadioScreen` public/package-visible cache, result, and control methods remain available.
- `initGui()` creates only the visible `Songs`/`Radio` header tabs and the Songs `Search`/`Charts`/`Playlists` subtab controls. The old Queue control remains an internal compatibility action only and is not visible.
- `HorizonRadioUiLayout` supplies positions for every visible widget.

- [ ] **Step 1: Add failing navigation assertions**

Add tests that assert `PANEL_WIDTH == 360`, `PANEL_HEIGHT == 322`, the source contains the logo texture and the two-column queue renderer, and initialized `buttonList` contains no visible upper scope controls. Add an initialization assertion that only the Songs/Radio header controls are visible in the main header.

- [ ] **Step 2: Run the focused GUI tests and verify failure**

Run: `./gradlew test --tests com.horizonradio.client.GuiLayoutTest`

Expected: the new geometry/navigation assertions fail against the current 300x285 single-column screen.

- [ ] **Step 3: Implement the header and tab layout**

Keep the existing button IDs used by result and playback tests, but reposition them to the Prototype structure. Add a logo `ResourceLocation`, two header `ControlButton`s, and three Songs mode buttons. Hide the compatibility Queue/Settings buttons. Update active-tab borders and button visibility from `currentTab` without adding playback-mode controls.

- [ ] **Step 4: Connect tab actions without changing client APIs**

Map Songs to the current Songs mode, Radio to `openRadio()`, and the three subtab buttons to Charts/Search/Playlist discovery. Keep the existing search and import fields separate, preserve Enter handling, and keep the old internal Queue action available only for compatibility tests and queue event handling.

- [ ] **Step 5: Run the focused GUI tests**

Run: `./gradlew test --tests com.horizonradio.client.GuiLayoutTest`

Expected: navigation and existing search/import dispatch tests pass; remaining failures identify only the old renderer and hitbox assumptions.

- [ ] **Step 6: Commit the navigation task**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: align HorizonRadio navigation with web prototype"
```

### Task 3: Render the WebPrototype body, Queue, footer, and controls

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- `drawChartsTab`, `drawSearchTab`, `drawPlaylistDiscoveryTab`, `drawRadioTab`, and `drawPlaylistTab` continue consuming the existing cached result lists.
- Add `drawQueuePanel`, `drawSongsBody`, `drawPanelHeader`, and `drawPrototypeButton` helpers that consume only screen state and `HorizonRadioClient` accessors.
- Existing `ControlButton` icon rendering remains the only icon rendering path.

- [ ] **Step 1: Add failing render-policy tests**

Add focused assertions for the body split, six visible row slots, the queue count/row control source strings, the logo texture, and the control order `Shuffle`, `Previous`, `Play/Pause`, `Next`, `Repeat`, `Favorite`.

- [ ] **Step 2: Run the tests and verify the renderer assertions fail**

Run: `./gradlew test --tests com.horizonradio.client.GuiLayoutTest`

Expected: the new renderer assertions fail because the current screen has no right-hand queue panel and renders the queue as its own tab.

- [ ] **Step 3: Implement panel backgrounds and header rendering**

Render the WebPrototype's dark panel, inset border, header, logo, Songs/Radio tabs, left content border, right Queue border, and footer borders with `drawRect`/`drawString`/`drawCenteredString`. Bind `HorizonRadioLogo.png` through `ResourceLocation` and use `Gui.func_152125_a` with alpha-safe white color.

- [ ] **Step 4: Implement song/radio result rows**

Render chart/search/playlist rows with the Prototype's note, title, artist/channel, duration, and queue action columns. Render active songs green, show favorite notes in Search, render radio rows with `LIVE`/heart and play/stop actions, and draw hover states without mutating state.

- [ ] **Step 5: Implement the right Queue panel**

Render `QUEUE (n/50)`, clear-all, current/next rows, titles, local artist/channel metadata, durations, remove buttons, active green row, queue scrollbar, and drag marker. Keep `sendRemove`, `sendClearPlaylist`, `sendPlayNow`, and `sendReorder` as the only mutations.

- [ ] **Step 6: Implement the Prototype footer and volume row**

Render the current title/artist, progress bar and time labels, the existing five icon controls plus favorite, and the full-width volume slider. Hide progress for active/paused radio and disable Shuffle/Repeat in the same states as the existing radio policy.

- [ ] **Step 7: Run focused tests and correct behavior regressions**

Run: `./gradlew test --tests com.horizonradio.client.GuiLayoutTest`

Expected: all direct queue, radio, favorites, seek, import, and scroll behavior remains green after the new renderer is in place.

- [ ] **Step 8: Commit the rendering task**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: render HorizonRadio web prototype panel in Minecraft"
```

### Task 4: Apply responsive rendering and native input hit-testing

**Files:**
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioScreen.java`
- Modify: `src/main/java/com/horizonradio/client/HorizonRadioVolumeSlider.java`
- Test: `src/test/java/com/horizonradio/client/HorizonRadioUiLayoutTest.java`
- Test: `src/test/java/com/horizonradio/client/GuiLayoutTest.java`

**Interfaces:**
- `drawScreen` wraps only the panel in the `HorizonRadioUiLayout` transform and passes logical mouse coordinates to `super.drawScreen`.
- `mouseClicked`, `mouseClickMove`, `mouseMovedOrUp`, and `handleMouseInput` use the same logical coordinates and preserve the existing drag/seek/scroll state machine.
- The existing slider persists values through `HorizonRadioClient`; no new volume state is introduced.

- [ ] **Step 1: Add failing input-scaling tests**

Verify that a standard `640x360` viewport keeps reference size, a `320x240` viewport scales down, and a logical queue-button hit remains inside the same queue column after conversion.

- [ ] **Step 2: Run the focused tests and verify the red state**

Run: `./gradlew test --tests com.horizonradio.client.HorizonRadioUiLayoutTest --tests com.horizonradio.client.GuiLayoutTest`

Expected: the new conversion/interaction assertions fail before the screen applies the layout transform.

- [ ] **Step 3: Implement the render transform and inverse mouse mapping**

Use `GL11.glPushMatrix`, translate to the screen center, scale by the layout value, and restore the matrix after panel rendering. Convert all mouse coordinates before hit-testing and before `super.drawScreen`; leave world background rendering outside the transform.

- [ ] **Step 4: Keep slider dragging and text-field focus correct**

Pass logical coordinates to both `GuiTextField` instances and the existing `HorizonRadioVolumeSlider`. Release seeking, scrollbar dragging, queue dragging, and slider dragging through the same Forge 1.7.10 hooks already used by Project1.

- [ ] **Step 5: Run the focused tests**

Run: `./gradlew test --tests com.horizonradio.client.HorizonRadioUiLayoutTest --tests com.horizonradio.client.GuiLayoutTest`

Expected: all geometry, hitbox, text-field, slider, drag-reorder, radio, and queue tests pass.

- [ ] **Step 6: Commit the responsive input task**

```bash
git add src/main/java/com/horizonradio/client/HorizonRadioScreen.java src/main/java/com/horizonradio/client/HorizonRadioVolumeSlider.java src/test/java/com/horizonradio/client/HorizonRadioUiLayoutTest.java src/test/java/com/horizonradio/client/GuiLayoutTest.java
git commit -m "feat: make HorizonRadio panel scale with Minecraft GUI size"
```

### Task 5: Complete verification and package the mod

**Files:**
- Modify: `README.md` if the documented panel dimensions still describe the old single-column screen.
- Modify: `docs/ARCHITECTURE.md` if its GUI section still describes the old tab layout.

- [ ] **Step 1: Run the complete test suite**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 2: Run the complete build**

Run: `./gradlew build`

Expected: `BUILD SUCCESSFUL`, including the reobfuscated jar and packaging checks.

- [ ] **Step 3: Inspect the final diff and asset list**

Run: `git status --short` and `git diff --stat HEAD~5..HEAD`.

Confirm that only the screen/layout tests, the logo asset, and directly related documentation changed; no server packet or unrelated gameplay file changed.

- [ ] **Step 4: Commit documentation corrections if needed**

```bash
git add README.md docs/ARCHITECTURE.md
git commit -m "docs: describe the prototype-aligned HorizonRadio UI"
```
