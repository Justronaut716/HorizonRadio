# Chart-Dauer beim Laden clientseitig vorauflösen

## Status

Angenommen am 13.08.2026.

## Problem

Der clientseitige Chart-Parser liefert für viele Chart-Einträge zunächst nur
Video-ID, Titel, Interpret und Thumbnail. Die Laufzeit bleibt leer. Die
aktuelle Lazy-Auflösung fragt die Metadaten erst beim Hinzufügen eines einzelnen
Charts ab und aktualisiert dadurch nur diesen Eintrag. Das führt zu einer
uneinheitlichen Chart-Anzeige und kann den Eindruck einer inkonsistenten
Playlist-Darstellung erzeugen.

## Ziel

Beim erfolgreichen Laden einer Chart-Liste sollen alle fehlenden Laufzeiten
clientseitig aufgelöst werden, bevor die Liste im GUI veröffentlicht wird. Der
Server darf dafür keine zusätzlichen Pakete oder Metadatenanfragen erhalten.

## Nicht-Ziele

- Keine Änderung am Server-Playlistmodell oder an den Playlist-Deltas.
- Keine Audiodatenübertragung und keine Änderung am lokalen Audio-Download.
- Keine erneute Abfrage von Chart-Einträgen, deren Laufzeit bereits bekannt ist.
- Keine Änderung an der normalen YouTube-Suche.

## Ablauf

1. Der Client lädt die Chart-Einträge wie bisher direkt von YouTube.
2. Für jeden Eintrag mit gültiger Laufzeit wird der Eintrag unverändert
   übernommen.
3. Für jeden Eintrag ohne gültige Laufzeit wird die vorhandene
   `ClientMetadataCache`-Auflösung verwendet. Identische oder bereits laufende
   Auflösungen werden über den Cache wiederverwendet.
4. Die Auflösungen laufen clientseitig und behalten die ursprüngliche
   Reihenfolge der Chart-Liste bei.
5. Erst wenn alle Auflösungen abgeschlossen sind, wird die vollständige
   Chart-Liste an Cache und GUI übergeben.
6. Liefert eine einzelne Auflösung keine verwertbaren Metadaten, bleibt der
   Eintrag erhalten und erhält als Laufzeit den Platzhalter `--:--`. Ein
   einzelner Fehler verwirft nicht die übrigen Charts.
7. Beim späteren Hinzufügen eines Charts wird die bereits aufgelöste Laufzeit
   aus dem lokalen Cache verwendet; es entsteht keine zweite Metadatenabfrage.

## Nebenläufigkeit und veraltete Antworten

Die Vorauflösung bleibt vollständig clientseitig. Die bestehende
Chart-Generation schützt davor, dass eine alte Region oder eine alte
Chart-Anfrage eine neuere Liste überschreibt. Wird die GUI geschlossen oder die
Verbindung getrennt, dürfen ausstehende Auflösungen keine neue Anzeige mehr
aktualisieren.

## Fehlerverhalten

Ungültige, nicht verfügbare oder zu lange Videos bleiben in der Chart-Liste,
werden aber mit `--:--` angezeigt. Das Hinzufügen validiert weiterhin die
endliche Laufzeit wie bisher und verwendet bei fehlenden Metadaten die bereits
vorhandene Fehlermeldung.

## Tests

Die Tests sollen mindestens abdecken:

- Charts mit fehlenden Laufzeiten werden erst nach Abschluss der Auflösungen
  veröffentlicht.
- Bekannte Laufzeiten werden unverändert übernommen und nicht erneut aufgelöst.
- Reihenfolge und Anzahl der Chart-Einträge bleiben erhalten.
- Ein fehlgeschlagener Metadatenaufruf erzeugt `--:--`, ohne andere Einträge zu
  verlieren.
- Das Hinzufügen eines voraufgelösten Charts verwendet die vorhandene Dauer.
- Eine veraltete Chart-Auflösung kann keine neuere Chart-Liste ersetzen.
- Der Chart-Ladevorgang verwendet weiterhin keinen Server-Transport.
