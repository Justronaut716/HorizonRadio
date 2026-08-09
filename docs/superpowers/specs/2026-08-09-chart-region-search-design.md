# Chart-Tab: Länder- und Regionssuche

## Ziel

Der Charts-Tab soll neben den bisherigen deutschen Charts auch die Weekly
Top-50-Charts anderer Länder und die globalen Charts laden können. Beim
Öffnen des Tabs werden standardmäßig die globalen Charts angezeigt. Spieler
sollen Länder in unterschiedlichen Schreibweisen und Sprachen suchen können,
ohne dass die Chart-Daten direkt von den Clients abgerufen werden.

## Festgelegter Umfang

- Der bestehende Weekly-Track-Chart bleibt die einzige Chart-Art in diesem
  Feature: `TRACKS` und `WEEKLY`.
- `GLOBAL` und alle ISO-3166-1-Länder werden als Regionen unterstützt.
- Die Auswahl ist pro Spieler sichtbar; der Server lädt und cached die Daten
  zentral pro Region.
- Die vorhandenen Chart-Ergebnisse, Queue-Aktionen, Play-Now, Refresh-Button
  und Operator-Berechtigungen bleiben erhalten.
- Es werden keine neuen Packet-IDs, externen Abhängigkeiten oder direkten
  Client-Anfragen an YouTube eingeführt.

## Nutzerverhalten

### Öffnen und Suche

1. Beim Öffnen des Charts-Tabs wird `GLOBAL` angefordert.
2. Das vorhandene Suchfeld wird auch im Charts-Tab eingeblendet und erhält
   einen Hinweis wie `Land oder Region suchen`.
3. Enter und der vorhandene Suchbutton starten die Suche.
4. Eine leere Eingabe setzt die Auswahl zurück auf `GLOBAL`.
5. Der Titel zeigt die kanonische Region, zum Beispiel `Top 50 Charts –
   Deutschland (Weekly)`.
6. Die Refresh-Schaltfläche aktualisiert die aktuell ausgewählte Region und
   behält die bestehende Operator-Prüfung für erzwungene Aktualisierungen.

Während einer Suche werden die bisher angezeigten Ergebnisse und die
Scrollposition zurückgesetzt. Bei einem Fehler der externen Chart-Anfrage
bleiben vorhandene Ergebnisse aus dem Cache sichtbar, sofern für die Region
welche vorhanden sind.

### Auflösung von Ländernamen

Ein gemeinsamer, client- und serverfähiger `ChartRegionCatalog` enthält alle
ISO-Ländercodes und `GLOBAL`. Für jeden Ländercode werden die Namen aus den
verfügbaren Java-Locale-Daten in verschiedenen Sprachen als Aliase registriert.
Zusätzlich werden gebräuchliche Sonderfälle explizit hinterlegt, darunter:

- `Deutschland`, `Germany` und weitere Locale-Namen → `DE`;
- `Amerika`, `America`, `USA`, `United States` und `United States of America`
  → `US`;
- `Global`, `Weltweit`, `Worldwide` und entsprechende gebräuchliche
  Übersetzungen → `GLOBAL`.

Die Normalisierung ist unabhängig von Groß-/Kleinschreibung und entfernt
Unicode-Akzente, Leerzeichen, Bindestriche und vergleichbare Trennzeichen.
ISO-Codes bleiben als direkte Eingabe gültig. Wenn ein Name auf mehrere
Länder passt, wird nicht willkürlich gewählt; der Spieler erhält eine
Fehlermeldung und kann den ISO-Code verwenden.

Der Client kann eine unbekannte oder mehrdeutige Eingabe vor dem Absenden
anzeigen und ablehnen. Der Server löst die Region trotzdem erneut auf und
weist ungültige Packet-Inhalte zurück, damit keine beliebigen Country-Codes an
YouTube weitergereicht werden.

## Architektur und Datenfluss

### Client

- `HorizonRadioScreen` verwendet das vorhandene Suchfeld auch im Charts-Tab.
- Der Client hält den aktuell ausgewählten kanonischen Regionscode und den
  Anzeigenamen lokal.
- `GLOBAL` ist der initiale Regionscode und wird bei leerer Suche wieder
  hergestellt.
- Chart-Ergebnisse werden weiterhin über den bestehenden
  `SearchResultsPacket`-Pfad empfangen; das `charts`-Kennzeichen bleibt
  maßgeblich für die Darstellung.

### Netzwerk

`RequestChartsPacket` erhält zusätzlich zum bisherigen `forceRefresh`-Flag den
kanonischen Regionscode. Die bestehende Packet-ID bleibt bestehen. Der
parameterlose bzw. bisherige Konstruktor bleibt als Kompatibilitätshelfer
erhalten und verwendet `GLOBAL`.

Die Client-Transport-Abstraktion und die Server-Hook-Signatur werden um den
Regionscode ergänzt. Bestehende Tests und Aufrufer können weiterhin die
Global-Variante verwenden.

### Server und YouTube

`PlaylistManager` validiert den Regionscode, liest den Regions-Cache und
entscheidet anhand der bestehenden Cache-TTL, ob sofort geantwortet oder
aktualisiert wird. Wartende Spieler und laufende Aktualisierungen werden pro
Region getrennt verwaltet, damit eine Suche nach Frankreich nicht auf eine
laufende Global-Aktualisierung warten muss.

`YouTubeService` erhält eine generische Chart-Methode, die den im
`ChartRegionCatalog` hinterlegten API-Country-Code verwendet. Der bisherige
deutsche Aufruf wird auf die neue Methode für `DE` umgestellt. Die übrige
Parserlogik für `musicAnalyticsSectionRenderer`, wöchentliche
`TOP_VIEWS_CHART`-Einträge, Rangbegrenzung und Duplikatfilter bleibt bestehen.

### Cache

`ChartCache` speichert Ergebnisse und Zeitstempel pro kanonischem Regionscode
und behält die bestehende Sieben-Tage-TTL. Das bisherige Einzelregionsformat
der Cache-Datei wird beim Laden als historischer `DE`-Cache interpretiert,
damit er nicht fälschlich als globaler Chart angezeigt wird. Leere oder
fehlgeschlagene Aktualisierungen überschreiben keinen vorhandenen gültigen
Cache.

## Fehlerverhalten

- Unbekannte oder mehrdeutige Eingaben lösen keine externe Anfrage aus; der
  aktuelle Chart bleibt sichtbar und der Spieler erhält eine Meldung.
- Ein nicht unterstützter oder leerer YouTube-Chart führt für eine neue Region
  zu einer leeren Ergebnisliste und einer verständlichen Servermeldung.
- Schlägt eine Aktualisierung für eine Region mit altem Cache fehl, werden die
  gecachten Ergebnisse weiter angezeigt.
- Schlägt eine Aktualisierung ohne vorhandenen Cache fehl, bleibt die Liste
  leer und der Ladezustand wird beendet.
- Eine fehlerhafte Antwort darf keinen Cache einer anderen Region verändern.

## Tests

### Katalog und Normalisierung

- Global-, ISO-Code-, deutscher und englischer Name werden auf denselben
  Regionscode aufgelöst.
- Mehrere weitere Locale-Namen und Akzentvarianten werden erkannt.
- Sonderfälle `Amerika`/`America`/`USA` und `Global` werden geprüft.
- Mehrdeutige Namen werden abgelehnt; unbekannte Namen erzeugen keine Region.

### Netzwerk und Service

- `RequestChartsPacket` round-tript Regionscode und `forceRefresh`.
- Die Global- und Länder-Request-Bodies enthalten den jeweils erwarteten
  Chart-Country-Code.
- Chart-Parsing, Daueranreicherung und bestehende deutsche Charts bleiben
  unverändert funktionsfähig.

### Cache und PlaylistManager

- Ergebnisse verschiedener Regionen bleiben voneinander getrennt.
- Der alte deutsche Einzelcache wird als `DE` geladen.
- Frische Region-Caches werden direkt geliefert; veraltete Caches lösen genau
  eine Aktualisierung pro Region aus.
- Fehlgeschlagene Aktualisierungen bewahren vorhandene Ergebnisse.
- Operator- und Force-Refresh-Regeln gelten unverändert für die ausgewählte
  Region.

### GUI und Regression

- Der Charts-Tab startet mit Global.
- Suche, Enter, Suchbutton, leere Eingabe und Regionsanzeige funktionieren.
- Unbekannte Eingaben behalten die bisherigen Ergebnisse.
- Queue, Play-Now, Refresh und bestehende Search-/Radio-Tabs bleiben grün.
- Die vollständige Gradle-Testsuite muss ohne Fehler durchlaufen.

## Nicht enthalten

- Tages-, Monats-, Genre- oder Künstler-Charts.
- Eine automatische Suche nach beliebigen YouTube-Suchbegriffen.
- Direkte YouTube-Anfragen vom Client.
- Eine Änderung der Chart-Anzahl oder der bestehenden Playlist-/Queue-Regeln.
