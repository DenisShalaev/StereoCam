# StereoCam — Dokumentation (Deutsch)

[Русский](README.ru.md) · [English](README.en.md)

> **Stand: Build 4. Die App wurde noch nie auf einem Telefon ausgeführt.** Der Android-Code wurde nicht kompiliert (in der Entwicklungsumgebung gibt es kein Android SDK). Nur die Ausrichtung des Bildpaars und der MPO-Writer wurden auf einer Desktop-JVM getestet. Alles, was die Kameras des Telefons betrifft, ist ungetestet.

## 1. Was ist das?

StereoCam macht **ein Stereofoto mit einem Telefon und einem Fingertipp**: Es nimmt gleichzeitig mit zwei rückseitigen Kameras auf (zum Beispiel Haupt- und Ultraweitwinkelkamera), richtet das Paar aus und speichert es in mehreren Formaten. Das Projekt wurde aus der Panorama-App StereoPano abgespalten und übernimmt deren Oberflächenstil, den `.vr.jpg`-Writer und die 7 Sprachen (en, de, fr, it, es, ru, uk).

Die Idee: Die beiden Objektive eines Telefons bilden bereits ein Stereopaar mit fester Basis (meist 1–2 cm). Fotografieren Sie ruhende Motive, nicht näher als etwa 30 cm; bei kürzerem Abstand wird der Unterschied zwischen beiden Ansichten zu groß. Für Makroaufnahmen eignet sich dieses Verfahren nicht (siehe Abschnitt 8).

## 2. So funktioniert es

1. **Kamera-Check (Startbildschirm).** Die App listet die Kameras des Telefons auf, findet „logische" Kameras aus mehreren physischen und zeigt die Paare, die einen Versuch wert sind. Der Bericht enthält je physischer Kamera: Brennweite (und Kleinbildäquivalent), Sensorgröße, größte JPEG-Größe, Naheinstellgrenze, Linsenposition relativ zur Hauptkamera (daraus ergibt sich die Basis) und die Art der Sensorsynchronisation.
2. **Aufnahme.** Die App öffnet die logische Kamera und erstellt eine Camera2-Sitzung mit drei Streams: Vorschau und zwei JPEG-Streams, jeweils an eine eigene physische Kamera gebunden. Eine Anfrage liefert zwei Bilder. Nimmt das Telefon die volle Auflösung nicht an, versucht die App 3264 und dann 2048 Pixel an der langen Seite.
3. **Verarbeitung.**
   - Beide Bilder werden nach Sensorausrichtung und Bildschirmdrehung gedreht.
   - Der Bildwinkel wird angeglichen: Das Bild der weitwinkligeren Kamera wird nach dem Verhältnis der äquivalenten Brennweiten mittig zugeschnitten.
   - Blockvergleich (normierte Korrelation) misst die Restfehler; daraus werden Höhenversatz, Drehung und Maßstabsunterschied angepasst und das rechte Bild korrigiert.
   - Die Ebene ohne Parallaxe wird auf den Median der Szene gelegt, die Ränder werden mit einem gemeinsamen Rahmen beschnitten.
   - Links und rechts werden aus den Linsenpositionen und der Bildschirmdrehung bestimmt. Die Taste „L/R tauschen" vertauscht sie von Hand.
4. **Speichern** nach `Pictures/StereoCam` (ab Android 10 über MediaStore).

### 2.1 Verschiebungsmodus (eine Kamera)

Funktioniert auf **jedem** Telefon, auch wenn zwei Kameras nicht gleichzeitig nutzbar sind. Dazu dient die Taste „Aufnahme mit Verschiebung (eine Kamera)" auf dem Startbildschirm; wurden keine Kamerapaare gefunden, wird sie zur Hauptaktion.

1. Der Regler „Motivabstand" (5 cm bis etwa 16 m) zeigt, **wie weit das Telefon verschoben werden soll**: etwa 1/30 des Abstands (z. B. 3 m → 10 cm, 1 m → 3,3 cm).
2. Die **erste Aufnahme** ist das linke Auge. Danach werden Belichtung, Weißabgleich und (wenn das Telefon manuellen Fokus erlaubt) der Fokus gesperrt; optische und Videostabilisierung werden abgeschaltet, damit sie die Verschiebung nicht aufheben.
3. Über der Live-Ansicht liegt eine **halbtransparente Kopie der ersten Aufnahme** („Geisterbild"): Telefon nach rechts verschieben, **ohne es zu drehen**, und beide Bilder zur Deckung bringen.
4. Die Zeile „Drehung: Nicken / Schwenk / Rollen" (Drehsensor) zeigt die Abweichung von der Lage der ersten Aufnahme. Grün bedeutet unter 1°, gelb unter 2,5°, rot mehr.
5. Die **zweite Aufnahme** durchläuft dieselbe Ausrichtung und wird in denselben Formaten gespeichert. „Neu beginnen" verwirft die erste Aufnahme. „L/R tauschen" kehrt die Richtung um: Die erste Aufnahme wird das rechte Auge, das Telefon wird nach links verschoben.

Ehrliche Grenzen: Die Sensoren des Telefons messen die Verschiebung nicht genau — sie wird nach Augenmaß anhand des Hinweises bestimmt; bewegte Motive (Personen, Zweige, Wasser) verursachen Fehler; die Ausrichtung korrigiert Höhenversatz, Neigung und Maßstab, aber keine falsche Basis. Der Modus wurde nicht auf einem Telefon getestet. Für Makro (5 cm Abstand → Verschiebung ≈ 1,7 mm) lässt sich die Verschiebung von Hand nicht halten; für Makro ist ein Drehteller besser — diesen Modus gibt es noch nicht.

### 2.2 Schnelles Linsenumschalten (nacheinander)

Für Telefone, die nicht zwei Kameras gleichzeitig freigeben (Android 11+ erforderlich). Dazu dient die Taste „Schnelles Linsenumschalten" auf dem Startbildschirm; außerdem wechselt die App von selbst in diesen Modus, wenn sich die Sitzung mit zwei Kameras nicht einrichten lässt.

1. Es wird **eine Sitzung der logischen Kamera** geöffnet: Vorschau und ein JPEG-Stream. Das ist eine Standardkombination, die Android garantiert.
2. Die erste Aufnahme entsteht mit dem ersten Objektiv. Sofort danach wird der Zoom auf das zweite Objektiv umgestellt — so wie es die Kamera-App des Herstellers tut. Die Zoomfaktoren werden aus den äquivalenten Brennweiten relativ zum bei Zoom 1,0 aktiven Objektiv berechnet.
3. Die App wartet, bis die Metadaten der Bilder (`ACTIVE_PHYSICAL_ID`) dreimal hintereinander das zweite Objektiv bestätigen. Hat es nach 0,9 s nicht umgeschaltet, wird der Zoom leicht nachgestellt (bis zu vier Versuche).
4. Es folgt die Aufnahme mit dem zweiten Objektiv, dann die übliche Verarbeitung und das Speichern. Der **Abstand zwischen beiden Aufnahmen in Millisekunden** steht in der Statuszeile.

Grenzen: Der Abstand liegt wahrscheinlich bei einigen hundert Millisekunden — Bewegung von Motiv oder Hand verursacht Fehler, die die Ausrichtung nicht beheben kann (sie entfernt Höhenversatz, Neigung und Maßstab, aber keine Parallaxe durch Wackeln). Der Modus funktioniert nur, wenn die logische Kamera des Telefons Objektive per Zoom umschaltet (Pixel und viele andere tun das, nicht alle). Nicht auf einem Telefon getestet.

## 3. Dateiformate

| Format | Verwendung |
|---|---|
| MPO (`.mpo`) | Standardformat für 3D-Fotos (Fujifilm, Nintendo 3DS, 3D-Fernseher) |
| Side-by-Side-JPEG | Links und rechts nebeneinander: VR-Brillen, Cardboard-Betrachter |
| `.vr.jpg` | Für den StereoPano Viewer am Computer (rechtes Bild im erweiterten XMP) |
| Rot-Cyan-Anaglyphe | Für Brillen mit Farbfiltern |
| Getrennte L/R-JPEG | Für externe Bildbearbeitung |

Standardmäßig sind MPO, Side-by-Side und automatische Ausrichtung eingeschaltet. Das MPO wird zuerst mit dem Typ `image/mpo` nach Pictures geschrieben; lehnt das System diesen Typ ab, landet die Datei in `Download/StereoCam`.

## 4. Installation

1. Projektordner in Android Studio (Hedgehog oder neuer) öffnen.
2. Gradle-Synchronisierung abwarten (bietet Studio an, den Gradle-Wrapper zu erstellen, zustimmen).
3. Telefon anschließen (Android 9 oder neuer, `minSdk 28`) und Run drücken.

## 5. Bedienung

1. App starten und Kamerazugriff erlauben.
2. Prüfen, ob Kamerapaare gefunden wurden. Wenn nicht, den Bericht unten auf dem Bildschirm lesen.
3. Ein Paar wählen (das erste hat die ähnlichsten Brennweiten und ist am einfachsten zu verarbeiten), Formate wählen und „Aufnahme starten" drücken.
4. Das Telefon so halten, dass die Objektive **auf einer waagerechten Linie** liegen. Die App wählt die Bildschirmausrichtung nach der Anordnung der Linsen.
5. Auslöser drücken. Das Ergebnis wird gespeichert, unten links erscheint eine Vorschau.
6. Wirkt die Tiefe vertauscht (Nahes wirkt fern), „L/R tauschen" drücken und neu aufnehmen.

## 6. Tipps für gutes Stereo

- Die Basis ist fest; wählen Sie die Entfernung entsprechend: Ein gutes Stereopaar hat das Motiv nicht näher als etwa das 30-Fache der Basis.
- Ruhende Motive aufnehmen: Die beiden Kameras sind nicht immer perfekt synchron (der Bericht zeigt `calibrated` oder `approximate`).
- Bei einem Paar „Haupt + Ultraweit" schneidet die App das weite Bild mittig zu. Die endgültige Auflösung bestimmt die engere Kamera.
- Objektive säubern und Motive ohne Struktur (klarer Himmel, weiße Wand) meiden: Die Ausrichtung stützt sich auf Bilddetails.

## 7. Kompatibilität: Welche Telefone funktionieren

### 7.1 Voraussetzungen

- Android 9 (API 28) oder neuer.
- Eine rückseitige „logische Mehrfachkamera" mit mindestens zwei physischen Kameras.
- Das Telefon muss die **physischen Kameras** Drittanbieter-Apps freigeben und **gleichzeitige** Aufnahme von zweien davon in einer Sitzung erlauben.

### 7.2 Die ehrliche Antwort: Es gibt keine geprüfte Liste

**Es gibt keine verlässliche öffentliche Liste von Telefonen, auf denen zwei physische Kameras in einer Drittanbieter-App gleichzeitig funktionieren, und bisher wurde kein Telefon mit StereoCam geprüft.** Gründe:

- Laut Android-Dokumentation stehen physische Kameras Apps ab Android 9 zur Verfügung, aber was die logische Kamera bietet, hängt „vollständig von der Implementierung des Herstellers" (Kamera-HAL) ab.
- Garantiert ist nur eine Mindestkombination: **Ein YUV- oder RAW-Stream der logischen Kamera lässt sich durch zwei Streams physischer Kameras gleicher Größe und gleichen Formats ersetzen.** Ab Android 10 muss eine logische Kamera Kombinationen mit physischen Streams nicht einmal mehr unterstützen.
- StereoCam verwendet **JPEG-Streams (möglicherweise unterschiedlicher Größe) plus Vorschau**. Diese Kombination ist **von den Android-Garantien nicht abgedeckt**. Das ist das größte technische Risiko (siehe Abschnitt 9).
- Hersteller können Kameras verbergen oder die Sitzung bei bestimmten Firmware-Ständen zum Absturz bringen.

### 7.3 Was aus öffentlichen Quellen bekannt ist

Das sind verstreute Angaben, teils von Nutzern und Entwicklern. Keine davon belegt, dass **gleichzeitige** Aufnahme mit zwei Kameras funktioniert.

| Marke / Modell | Was berichtet wird | Verlässlichkeit |
|---|---|---|
| Google Pixel 3 | In der Android-Dokumentation als Beispiel für ein Gerät mit logischer Kamera genannt | Google-Dokumentation (2018), zur API, nicht zur gleichzeitigen Aufnahme |
| Huawei Mate 20 | In einem Android-Developers-Artikel als Gerät mit Multi-Camera-API zum Start von Android 9 genannt | Artikel von 2018; das Gerät erhält längst keine Updates mehr |
| Pixel (allgemein) | „Zeigen alles" | Wiedergabe in einem Drittanbieter-Podcast; schwache Quelle |
| Samsung Galaxy S23 Ultra | Zeigt alle Objektive über die Standard-API | Dieselbe schwache Quelle |
| Samsung Galaxy S22–S25 | In einem Thread im Samsung-Entwicklerforum antwortet ein Nutzer, Samsung liefere keine Telekamera-Informationen, man müsse CameraX verwenden | Einzelne Forenmeinung, keine offizielle Samsung-Antwort |
| OnePlus 9 (OxygenOS 12) | Das Android-12-Update entfernte den Zugriff von Drittanbieter-Apps auf Zusatzkameras | Wiedergabe in einer Drittquelle |
| OnePlus 7 Pro | Die Ultraweitwinkelkamera war für eine Drittanbieter-App nicht sichtbar (für Open Camera sichtbar) | Entwicklerblog, 2021 |
| Motorola (Modelle nicht genannt) | Die Ultraweitwinkelkamera steht Drittanbieter-Apps nicht zur Verfügung | Entwicklerblog, 2021 |
| Xiaomi POCO X8 Pro (Android 16, HyperOS 3) | Eine Sitzung über die logische Kamera mit physischen Streams bricht mit `ERROR_CAMERA_DEVICE` ab, solange die Videostabilisierung auf den physischen Kameras aktiv ist; ohne sie funktioniert es laut Autor | Einzelner Forenbericht, keine Bestätigung von Xiaomi |

Diese Angaben veralten schnell: Firmware-Updates ändern das Verhalten. Verwenden Sie die Tabelle als Anhaltspunkt, nicht als Garantie.

### 7.4 So prüfen Sie Ihr Telefon

App installieren und den Startbildschirm öffnen:

- **Paare aufgelistet** — das Telefon zeigt der App zwei physische Kameras (notwendig, aber nicht hinreichend).
- **„Kein nutzbares Kamerapaar"** — das Telefon gibt die physischen Kameras nicht frei: der Zwei-Kamera-Modus ist nicht verfügbar, nutzen Sie den Verschiebungsmodus (Abschnitt 2.1).
- Danach braucht es eine echte Aufnahme. Zeigt die App `Modus 1/2/3: …` und speichert eine Datei, funktioniert die gleichzeitige Aufnahme. Der Modus ist die Auflösungsstufe (1 — Maximum, 2 — bis 3264, 3 — bis 2048).
- Meldet die App, das Telefon verweigere den gleichzeitigen Betrieb beider Kameras, wechselt sie von selbst zum schnellen Linsenumschalten (Abschnitt 2.2). Ist nur eines der beiden Bilder eingetroffen, funktioniert das Paar nicht für gleichzeitige Aufnahmen; probieren Sie den Modus aus 2.2.

### 7.5 Tabelle geprüfter Geräte (wird aus Berichten gefüllt)

Vorerst leer. Haben Sie StereoCam auf Ihrem Telefon getestet, senden Sie den Bericht („Bericht teilen" auf dem Startbildschirm) und eine Notiz, ob die Aufnahme funktionierte und in welchem Modus. Die Daten werden in `docs/devices.csv` gesammelt.

| Hersteller | Modell | Android | Paar | Modus | Ergebnis |
|---|---|---|---|---|---|
| — | — | — | — | — | noch keine Daten |

## 8. Einschränkungen

- Nichts, was Android betrifft, wurde auf einem Telefon getestet: Kamerasuche, Sitzung mit zwei physischen Streams, Vorschau und deren Drehung, Speichern über MediaStore.
- Makro: Die feste Linsenbasis (1–2 cm) ist für Motive näher als etwa 30 cm zu groß. Ein Verschiebungsmodus existiert (Abschnitt 2.1), aber für Makro lässt sich eine Verschiebung von wenigen Millimetern von Hand nicht halten; ein Drehtellermodus ist noch nicht umgesetzt.
- Die automatische Ausrichtung wurde nur an einem synthetischen Paar getestet. Reale Szenen (Spiegelungen, sich wiederholende Texturen, wenig Details) können zu einer falschen Anpassung führen. Die Ausrichtung lässt sich in den Einstellungen abschalten.
- Links/rechts wird aus den Linsenpositionen bestimmt; bei einem Telefon ohne Positionsdaten wird die Reihenfolge „A ist links" angenommen und lässt sich per Taste korrigieren.
- Speicher: Fotos sind auf 3600 Pixel an der langen Seite begrenzt; die App fordert einen großen Heap an.
- Die Übersetzungen wurden nicht von Muttersprachlern geprüft.

## 9. Bekannte Risiken und nächste Schritte

- **Rückfallmodi.** Funktioniert die gleichzeitige Aufnahme nicht, gibt es das schnelle Linsenumschalten (2.2) und die Verschiebung mit einer Kamera (2.1).
- **Stream-Kombination.** Robuster wäre es, zwei `YUV_420_888`-Streams gleicher Größe zu verwenden und das JPEG selbst zu kodieren; diese Kombination ist unter Android 9 für Geräte mit physischen Kameras garantiert. Die Vorschau müsste in diesem Modus aus einem der Streams kommen. Das ist der zweite Schritt, der sich lohnt, falls der erste auf Ihrem Telefon nicht funktioniert.
- Ab Android 10 lässt sich die Kombination vorab mit `CameraDevice.isSessionConfigurationSupported` prüfen und das Ergebnis auf dem Startbildschirm anzeigen.
- Bei manchen Telefonen sind die physischen Kameras nicht in `getCameraIdList()` aufgeführt (verborgen), aber über ihre ID erreichbar; die App fragt sie direkt ab.

## 10. Fehlersuche

| Meldung | Bedeutung |
|---|---|
| „Kein nutzbares Kamerapaar" | Das Telefon gibt Apps keine zwei physischen Kameras frei |
| „Dieses Telefon verweigert den gleichzeitigen Betrieb beider Kameras" | Die Sitzung ließ sich bei keiner der drei Auflösungen einrichten |
| „Das Telefon hat nur eines der beiden Bilder geliefert" | Das zweite Bild kam nicht innerhalb von 9 Sekunden |
| „Die Kamera ließ sich nicht öffnen" | Die Kamera wird von einer anderen App benutzt oder ist nicht verfügbar |

## 11. Projektstruktur

`ProbeActivity` (Check und Einstellungen) · `DualCaptureActivity` (Aufnahme) · `DualProbe` (Paarsuche) · `Processor` (Verarbeitung und Schreiben) · `StereoAligner` (reines Java: Schätzung und Korrektur) · `MpoWriter` · `VrJpgWriter`, `ExifGps` (aus StereoPano) · `Prefs` · `Ui` · `tools/gen_strings.py` (Strings in 7 Sprachen). Desktop-JVM-Test: `test/AlignTest.java`.

## 12. Quellen

- [Multi-camera API — Android Developers](https://developer.android.com/media/camera/camera2/multi-camera)
- [Multi-camera support — Android Open Source Project](https://source.android.com/docs/core/camera/multi-camera)
- [Getting the Most from the New Multi-Camera API — Android Developers (Medium)](https://medium.com/androiddevelopers/getting-the-most-from-the-new-multi-camera-api-5155fb3d77d9)
- [Android Camera Fragmentation: Why Apps See Different Lenses](https://www.myweirdprompts.com/episode/android-camera-fragmentation-physical-lenses/)
- [Every Camera, Every Angle on Android — GameChanger Tech Blog](https://tech.gc.com/every-camera-every-angle-on-android/)
- [Telephoto access via third party application — Samsung Developer Forums](https://forum.developer.samsung.com/t/telephoto-access-via-third-party-application/42137)
- [Camera2 API Issue: Physical Camera fails via Logical Camera Flow … POCO X8 Pro](https://android-overflow.vercel.app/thread/79931897)
