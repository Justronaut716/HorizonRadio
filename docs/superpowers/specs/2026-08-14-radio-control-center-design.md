# Radio-Steuerung im Control Center

## Ziel

Die Radio-Wiedergabe soll sich im Control Center wie ein eigener Queue-Eintrag verhalten:

- Die aktive Radio-Station wird im Queue-Tab grün markiert.
- Der mittlere Button pausiert den lokalen Radio-Stream, ohne die Radio-Station aus der Queue zu entfernen.
- Der Play-Button lädt nach der Pause dieselbe Station erneut.
- Previous startet den Song, der beim Start des Radios unterbrochen wurde.
- Next/Skip beendet das Radio und startet den nächsten normalen Song aus der Queue.

Die Queue und die Wiedergabe bleiben serverautoritativ. Die bestehende clientseitige Radio-Auflösung und die vorhandenen Track-Sync-/Control-Pakete werden weiterverwendet; der Server lädt keine Radio- oder Playlist-Medien.

## Architektur und Datenfluss

### Serverzustand

`PlaylistState` erhält einen Radio-spezifischen Pause-Zustand, der die Radio-Queueposition und die Station beibehält, aber `playing` auf `false` setzt. `PlaylistManager.handleStopRadio` verwendet diesen Zustand und sendet eine Stop-Synchronisierung nur zum Beenden des lokalen Streams. Die Queue wird dabei nicht mutiert.

Beim Start einer Station wird der aktuell laufende finite Song als Rücksprung-Song gespeichert und aus dem aktiven Ablauf genommen. Die Station steht anschließend an Position 0; die übrigen normalen Queue-Songs bleiben dahinter. Wird während eines laufenden Radios eine andere Station gewählt, wird nur die Station an Position 0 ersetzt.

`handlePreviousTrack` darf bei aktivem oder pausiertem Radio den gespeicherten Rücksprung-Song auswählen und direkt starten. `handleSkipTrack` darf bei aktivem oder pausiertem Radio die Radio-Position entfernen und anschließend den ersten normalen Queue-Song starten. Gibt es keinen passenden Song, wird die Wiedergabe beendet.

### Clientzustand und UI

Die Clientseite behält nach einer Radio-Stop-Synchronisierung die Station als pausierbare Radio-Präsentation, damit der Play-Button unabhängig vom aktuellen Tab dieselbe UUID erneut senden kann. Ein aktiver Radio-Track bleibt dabei weiterhin als Radioquelle erkennbar; pausierte Radios werden nicht als aktiv grün markiert.

Im Queue-Tab wird eine Zeile nur dann als aktiv markiert, wenn ihr Eintrag eine Radioquelle ist und UUID und aktive Radio-Präsentation übereinstimmen. Die bisherigen Regeln für finite Songs bleiben erhalten.

Previous und Next bleiben im Radio-Modus anklickbar. Shuffle und Loop bleiben für Radio deaktiviert. Der mittlere Button zeigt bei aktivem Radio Pause und bei pausiertem Radio Play.

## Fehler- und Randfälle

- Eine pausierte oder aktive Station ohne Rücksprung-Song macht Previous zu einem sicheren No-op.
- Skip entfernt nur die Radio-Position; normale Queue-Songs werden nicht verworfen.
- Wird die Radio-Station aus einer autoritativen Queue-Aktualisierung entfernt, stoppt der lokale Radio-Player und die pausierbare Radio-Präsentation wird verworfen.
- Die direkte Song-/Radio-Auswahl und die bestehende Regel für volle Queues bleiben unverändert.

## Tests

- `PlaylistStateTest`: Radio-Start speichert den unterbrochenen Song, pausiert ohne Queue-Löschung und behält die Nachfolger-Reihenfolge.
- `PlaylistManagerTest`: Pause/Resume, Previous und Skip im Radio-Modus sowie die bisherige Radio-Queue-Synchronisierung.
- `GuiLayoutTest`: aktive Radio-Zeile wird grün markiert; Radio-Controls bleiben für Previous/Next aktiv und der mittlere Button wechselt zwischen Pause und Play.
- `HorizonRadioClientTrackSyncTest`: Stop-Synchronisierung beendet den lokalen Radio-Stream, bewahrt aber die Station zum erneuten Laden.
- Vollständige Gradle-Tests, Formatter-Prüfung und Build vor Abschluss.
