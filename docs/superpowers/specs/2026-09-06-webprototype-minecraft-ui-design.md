# WebPrototype Minecraft UI Design

## Ziel

Die bestehende Forge-1.7.10-Oberfläche von HorizonRadio soll die sichtbare
Gestaltung und die im WebPrototype demonstrierten Interaktionen übernehmen.
Die drei oberen Prototype-Schalter `Client`, `Server` und `Group` gehören
ausdrücklich nicht zum Umfang und werden in Minecraft weder gerendert noch
als neue Funktion eingeführt.

## Zielansicht

Die Minecraft-GUI besteht aus einem dunklen, pixeligen HorizonRadio-Panel mit
folgenden Bereichen:

- Kopfzeile mit HorizonRadio-Logo und den Haupttabs `Songs` und `Radio`.
- Songs-Untertabs `Search`, `Charts` und `Playlists`.
- Linke Inhaltsfläche mit Suchfeld, Such-Button, Refresh-Aktion, Ergebnisliste
  und scrollbarer Darstellung.
- Rechte Queue-Fläche mit Anzahl, aktuellem Eintrag, nächsten Einträgen,
  Entfernen-Buttons und eigener Scrollposition.
- Untere Wiedergabefläche mit Titel, Interpret/Kanal, Zeit-/Fortschrittsleiste,
  Shuffle, Previous, Play/Pause, Next, Repeat und Favoriten.
- Darunter eine horizontale Lautstärkeleiste.

Die Referenzfarben bleiben bei den vorhandenen WebPrototype-Werten: dunkle
Grauwerte für Panel und Listen, helle Grauwerte für Text und Rahmen sowie
`#315b38`/`#79d38a`/`#a8d7ab` für aktive und positive Zustände. Buttons erhalten
Minecraft-kompatible innere Highlight-/Shadow-Linien, Hover-Zustände und
aktive grüne Hervorhebung. Die vorhandenen 128x128-Control-Icons werden in den
Referenzgrößen gerendert. Das Logo wird als PNG-Texture eingebunden.

## Interaktionen

Die bestehende Projekt1-Architektur bleibt die Quelle für Zustandsänderungen:

- Charts suchen nach Land/ISO-Code, laden Ergebnisse, refreshen und queue'n
  einzelne oder alle Einträge.
- Search nutzt die vorhandene lokale YouTube-Suche und Direct-Play-Auflösung.
- Playlists importiert weiterhin lokal eine YouTube-Playlist und übergibt nur
  explizite Queue-Aktionen an den bestehenden Client-Pfad.
- Radio lädt beliebte Sender, sucht Sender, startet eine Station und zeigt den
  Live-Zustand im Queue- und Control-Center.
- Queue-Einträge lassen sich anklicken, entfernen, scrollen und per nativer
  Mausbewegung umsortieren. Die laufende erste Zeile bleibt entsprechend der
  bestehenden Serverregeln nicht verschiebbar.
- Die Fortschrittsleiste unterstützt Seek bei endlichen Titeln; bei Radio wird
  sie ausgeblendet. Shuffle und Repeat bleiben während Radio deaktiviert.
- Favoriten und Lautstärke verwenden die bestehenden `HorizonRadioClient`-
  APIs und die vorhandene Client-Konfiguration.
- Bestehende Ladefortschritte, kurze Ergebnis-Reveal-Verzögerung, Hover und
  gedrückte/aktive Button-Zustände werden in der Minecraft-Render-/Input-Schicht
  nachgebildet.

## Architektur

`HorizonRadioScreen` bleibt der einzige Screen für diese Oberfläche. Die
Darstellung wird auf das zweispaltige Prototype-Layout umgestellt, während
`HorizonRadioClient`, Netzwerkpakete, Medienpfade und Serverzustand unverändert
bleiben. Vorhandene `GuiTextField`-, Slider- und `ControlButton`-Strukturen
werden weiterverwendet; Panel, Listenzeilen, Queue und Hover-Flächen werden mit
Minecrafts `Gui`-Primitive gerendert, damit die Web-CSS-Geometrie nicht als
HTML/CSS übernommen wird.

Die Referenzgeometrie wird in einer festen logischen Panel-Koordinate berechnet
und proportional an die verfügbare `GuiScreen`-Fläche angepasst. Mauspositionen
werden durch dieselbe Transformation zurückgerechnet. Dadurch bleibt die
Anordnung bei unterschiedlichen Auflösungen und GUI-Scales stabil, ohne
Minecrafts Welt-Hintergrund oder Gameplay-Rendering zu verändern.

Die bestehende `PlaybackMode`-Persistenz bleibt intern erhalten, wird aber aus
dieser UI entfernt: Die Prototype-Schalter für `Client`, `Server` und `Group`
werden nicht portiert. Die aktuelle Client-/Server-Auswahl bleibt damit
technisch unverändert und wird nicht durch neue UI-Aktionen verändert.

## Assets

- Übernahme von `img/horizonradio-logo.png` aus Projekt2 als
  `assets/horizonradio/textures/gui/HorizonRadioLogo.png`.
- Die sieben bereits identischen 128x128-Control-Textures aus Projekt1 werden
  weiterverwendet.
- Das WebPrototype-Hintergrundbild wird nicht kopiert, weil Minecraft den
  geladenen Welt-Hintergrund rendert und Projekt1 dafür bereits
  `drawDefaultBackground()` nutzt.

## Verifikation

- Bestehende Client-/GUI-Tests bleiben grün; neue Tests prüfen die
  zweispaltige Layoutgeometrie, die sichtbaren Haupt-/Untertabs, Queue-Hitboxen,
  die ausgelassenen oberen Prototype-Schalter sowie die unveränderte
  Playback-/Search-/Radio-Dispatch-Logik.
- `./gradlew test` wird nach jedem fokussierten Testzyklus ausgeführt.
- `./gradlew build` ist die abschließende Compile-, Test- und Packaging-Prüfung.
- Für die visuelle Prüfung werden die Projekt2-Screenshots als Referenz für
  Default-Songs, Radio, Playlist und leere/geladene Zustände verwendet.

## Bewusste Grenzen

Die obere `Client`/`Server`/`Group`-Leiste und die dazugehörigen Umschaltungen
werden nicht implementiert, wie vom Auftrag festgelegt. Alle übrigen Daten
und Aktionen stammen aus Projekt1; der Minecraft-Screen erfindet keine
Serverdaten und kopiert keine Web-Implementierung.
