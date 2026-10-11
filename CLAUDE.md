# DeepMine

Clientseitiger Fabric-Mod für Minecraft 26.2. Gräbt einen 1 Block breiten und
standardmäßig 4 Blöcke hohen Gang als quadratische Auswärtsspirale um einen
Beacon, solange Haste II reicht.

## Status

Kompiliert und läuft. **Im Spiel nur teilweise getestet** — Grundlauf und
Shulker-Einlagern sind erprobt, Kampf, Heilen, Essen, Boden-Schließen und
Kies-Behandlung sind ungetestet oder nur einmal gesehen. Wer hier etwas ändert,
sollte davon ausgehen, dass Fehler noch nicht gefunden sind, nicht dass es keine
gibt.

```
./gradlew build          # JDK 25 nötig, Jar landet in build/libs/
```

JAVA_HOME zeigt auf diesem Rechner auf JDK 21. Vorher setzen:
`export JAVA_HOME="/c/Program Files/Java/jdk-25.0.4.1"`.

## Struktur

| Datei | Inhalt |
|---|---|
| `Spiral.java` | Reine Geometrie, keine Minecraft-Abhängigkeit. Isoliert testbar. |
| `MinerTask.java` | Zustandsmaschine: graben, laufen, Fackeln, Erz, Kampf, Heilen, Essen, Boden, Shulker, Abbruchbedingungen. Hier stecken die Risiken. |
| `BoxFilter.java` | Was in einen Shulker darf (ONLY/EXCEPT/ALL, nur volle Stapel). |
| `Settings.java` | Einstellungen plus Laden/Speichern `config/deepmine.json`. |
| `JobStore.java` | Laufender Auftrag, `config/deepmine-job.json`. |
| `Chat.java` | Alle Chat-Ausgaben, Farben, Sprachschlüssel. |
| `DeepMine.java` | Einstiegspunkt, Brigadier-Befehle, Tick-Hook. |

Wegen `splitEnvironmentSourceSets()` im Buildskript liegen die Quellen unter
`src/client/java/`, nicht unter `src/main/java/`.

## Arbeitsweise

**Gegen die echten Mappings prüfen statt raten.** Die Minecraft-Jars liegen
entpackt im Loom-Cache:

```
javap -classpath 'C:\Users\yanni\.gradle\caches\fabric-loom\26.2\minecraft-client.jar' net.minecraft.world.effect.MobEffects
```

In 26.1+ gibt es nur noch Mojang-Mappings, Yarn wird nicht mehr gepflegt. Allein
in diesem Projekt sind bisher aufgefallen: `MobEffects.DIG_SPEED` → `HASTE`,
`displayClientMessage` → `sendSystemMessage`, `ClientCommandManager` →
`ClientCommands`, `handleInventoryMouseClick` → `handleContainerInput`,
`ClickType` → `ContainerInput`, `ResourceLocation` → `Identifier`,
`Item.getDescription()` existiert nicht. Älterer Beispielcode aus dem Netz ist
hier regelmäßig falsch.

**Texte gehören in die Sprachdateien**, nicht in den Code. `assets/deepmine/lang/`
hat `en_us.json` und `de_de.json`; beide müssen dieselben Schlüssel haben, sonst
erscheint im Chat der rohe Schlüssel. Der Compiler prüft das nicht.

## Entscheidungen, die absichtlich so sind

Bitte nicht „aufräumen", das sind bewusste Kompromisse:

- **Abbau nur über `gameMode.continueDestroyBlock`.** Das ist die Vanilla-Methode
  beim Halten der linken Maustaste. Timing und Packets macht das Spiel selbst.
  Der Mod darf nicht schneller abbauen können als ein Mensch. Keine handgebauten
  Break-Packets. Die 5-Tick-Pause zwischen Blöcken ist Vanilla und bleibt.
- **Dasselbe gilt für Setzen, Öffnen, Abbauen und Slot-Tausch**: alles über
  `useItemOn`, `continueDestroyBlock` und `handleContainerInput`.
- **Spirale über eine Besucht-Liste, nicht über Blockabfragen.** Ein Wandfolger,
  der prüft „ist links noch ein Block", wird von jeder natürlichen Höhle
  getäuscht und fährt sich am Rundenende fest. Regel bleibt: links, sonst
  geradeaus, sonst rechts, sonst fertig.
- **Gespeichert wird die Schrittzahl, nicht die Besucht-Liste.** Die Regel ist
  deterministisch, `Spiral.restore` spielt sie nach. 10.000 Zellen in einer
  JSON-Datei wären Unsinn.
- **Hotbar-Slots statt Verzauberungsprüfung** für die Silk-Touch-Picke. Die
  Component- und Registry-API dafür wandert von Version zu Version.
- **Der Einlagerschutz hängt am Slot, nicht am Item.** Sonst würde ein
  EXCEPT-Filter die eigene Ausrüstung wegpacken. Achtung: `Slot.index` ist die
  Position im *Menü* (Hotbar 1 = 54), der Inventarplatz ist `getContainerSlot()`.
  Diese Verwechslung war schon einmal ein Bug.
- **Fackeln bei Blocklicht 0.** Seit 1.18 exakt die Spawn-Bedingung. Eine Fackel
  gibt 14 und verliert pro Block eins, ergibt also von selbst rund alle 14
  Blöcke eine. Keine geratene Konstante einbauen.
- **Fortbewegung über `setDeltaMovement`** statt über die Eingabe-Impulse. Ist
  unelegant, aber die Input-Klasse hat sich zwischen 1.21.4 und 1.21.5 von einem
  veränderbaren Objekt zu einem Record gewandelt.
- **Essen über die gehaltene Benutzen-Taste.** `Minecraft.tick` bricht jedes
  Item-Benutzen ab, sobald `keyUse` nicht gedrückt ist — ein einzelner
  `useItem`-Aufruf wird im nächsten Tick verworfen. Die Taste muss in jedem
  Ausgang wieder losgelassen werden, auch bei `stop`.
- **Kampf hat Vorrang vor allen Abbruchgründen.** Ein Mod, der bei niedrigem
  Leben stehen bleibt statt sich zu wehren, bringt einen AFK-Spieler um. Das ist
  einmal passiert; der Abbruch bei Leben unter 12 gilt nur noch ohne Schwert.
- **Kein Rückweg zur Kiste, kein Pathfinding.** Der Shulker kommt mit.

## Testen

In einer Singleplayer-Kopie, nicht auf dem Server:

```
/deepmine start 8 4        Radius 8, Höhe 4 — vier Ringe, läuft in zwei Minuten
```

Beacon mit Stufe-4-Pyramide, Schacht nach oben frei, **Eile als Primär- UND
Sekundäreffekt** (sonst nur Eile I und Abbruch nach 5 s), mindestens 5 Blöcke
vom Beacon weg stellen.

Der interessanteste Fehlerfall ist der **Rundenschluss**: der Moment, in dem
links und geradeaus beide erledigt sind und die Spirale nach rechts einen Ring
nach außen springen muss. Die Geometrie dahinter ist isoliert geprüft (208 von
208 Zellen bei Radius 8, 33 Vergleichspunkte für `restore` bei Radius 50) — was
dort schiefgehen kann, ist das Mitlaufen des Spielers, nicht die Rechnung.

Für Shulker-Fehler sind die Logzeilen `DeepMine: Kiste zu` und
`Shulker nach dem Aufsammeln` in `logs/latest.log` die erste Anlaufstelle: die
erste zeigt die Client-Vorhersage, die zweite die Server-Wahrheit.

## Größenordnung im Echtbetrieb

Radius 50, Kernradius 4: 10.120 Säulen, mal 4 Blöcke Höhe sind 40.480 Blöcke,
also gut 23 Shulkerboxen und rund zwei Stunden Laufzeit.
