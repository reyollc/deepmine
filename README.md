# DeepMine

Client-side Fabric mod for Minecraft 26.2 and 26.3. Digs a 1-block-wide, 4-block-high
corridor as a square outward spiral around a beacon, for as long as the beacon's
Haste II reaches. Ores come out with Silk Touch, torches go in wherever it is
dark, and the rubble goes into shulker boxes you carry with you.

Built for one job: standing next to a level-4 beacon deep underground and
clearing the whole Haste zone without you holding the mouse button for two hours.

> **Status: early.** The mod works, but it has seen far more code review than
> playtime. Expect rough edges, and please report what you hit — an issue with
> the chat message and the position is enough to act on.

> **Single-player and servers that allow it.** The mod moves your character,
> fights back and places blocks on its own. Most multiplayer servers consider
> that cheating. Check the rules of your server before using it there; getting
> banned is on you.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 and 26.3 |
| Mod loader | Fabric, loader 0.18.4 or newer |
| Dependency | Fabric API |
| Java | 25 |

## Setup in game

1. Place a beacon with a **complete level-4 pyramid** (164 blocks), with a clear
   shaft straight up to the sky — otherwise the beacon will not activate.
2. Activate it with **Haste as both the primary and the secondary power**. That
   is what gives Haste II; picking Regeneration on the right gives you Haste I
   and the mod stops after five seconds. Check that your effect bar really
   says Haste II.
3. Stand at least 5 blocks away from the beacon, at the height you want the
   corridor floor to be, and look in the direction you want to start.
4. Fill your hotbar (defaults below), then `/deepmine start`.

A level-4 beacon reaches 50 blocks, which is the default radius.

## Default hotbar

| Slot | Contents |
|---|---|
| 1 | Sword |
| 2 | Pickaxe (Efficiency, Fortune) |
| 3 | Pickaxe with Silk Touch |
| 4 | Torches |
| 5 | Building blocks for holes in the floor |
| 6 | Empty shulker box — takes everything except cobbled deepslate |
| 7 | Empty shulker box — takes only cobbled deepslate |
| 8 | Food |

Every slot is configurable, and all settings are saved automatically to
`config/deepmine.json`. Spare shulker boxes can go anywhere in your main
inventory — the mod swaps them in as the ones in the hotbar fill up.

## Commands

```
/deepmine help                        list all commands
/deepmine start [radius] [height]     start the spiral, defaults 50 and 4
/deepmine stop
/deepmine resume                      continue, also after a restart
/deepmine status                      version, ring, cells done, state
/deepmine forget                      discard the saved job

/deepmine slots <pick> <silk> <torch>
/deepmine sword <slot> | off
/deepmine floor <slot> | off
/deepmine food <slot> | off
/deepmine durability <left>           stop at remaining durability, default 40

/deepmine box <slot> only <item>      this shulker takes only <item>
/deepmine box <slot> except <item>    ... everything except <item>
/deepmine box <slot> all              ... everything
/deepmine box <slot> fullstacks on|off
/deepmine box list | clear

/deepmine silk <block> | list | clear  mine with Silk Touch on top of ores
/deepmine config | config reset
```

Calling a `box` or `silk` command repeatedly adds to the list rather than
replacing it.

## What it does on its own

**Mines** with the Silk Touch pickaxe for ores and for anything on the Silk
list (gravel by default, because Fortune III turns gravel into nothing but
flint), with the normal pickaxe for everything else. Ore in the walls of a
finished column is taken along.

**Lights** the corridor whenever block light drops to 0 — exactly the hostile
spawn condition since 1.18. A torch gives 14 and loses one per block, so that
works out to roughly one every 14 blocks without a hardcoded interval.

**Bridges** holes: if the block under the next cell would not carry you, it
places one from the floor slot first and only then walks.

**Handles falling gravel and sand**: waits until nothing is in the air any more,
re-checks the column, and drops a torch into the top of the shaft so a whole
nest rattles through as items instead of being mined block by block.

**Fights back** with the sword as soon as an enemy comes close or damage lands,
and only swings on a full attack bar. It never chases into a cave: each step
towards a target needs solid ground. Afterwards it waits to regenerate instead
of stopping, and eats when hunger gets low.

**Stores** into shulker boxes: places one from the hotbar, fills it with what
its filter allows, breaks it, picks it up and carries on. It reads what is
already inside a shulker from the item itself, so a box never gets mixed and
part-filled boxes are topped up before an empty one is opened.

**Remembers the job** in `config/deepmine-job.json`. Leaving the server or
closing the game is fine: come back, stand in the corridor, `/deepmine resume`.
The dimension has to match, and the mod tells you where the next cell is.

## When it stops by itself

Always with the reason and the position in chat, and always resumable with
`/deepmine resume`: pickaxe below the durability threshold, Haste II gone for
five seconds (so you have left the beacon zone), hunger low with no food left,
inventory full with no usable shulker left, lava or water behind the next block,
sculk nearby, no progress for three seconds, target out of reach, falling blocks
that will not settle, or losing health with no enemy in sight.

## Deliberate design decisions

These are compromises, not oversights:

- **Mining goes exclusively through the vanilla "hold left mouse button" path.**
  Timing, progress and packets are the game's own. The mod therefore cannot mine
  faster than you can by hand — including vanilla's five-tick pause between
  blocks. There is no hand-built break packet anywhere in it.
- **The spiral runs off a visited list, not off block queries.** A wall follower
  that asks "is there still a block to my left" is fooled by every natural cave
  and gets stuck at the end of a ring. The rule stays left, else straight, else
  right, else done — only the data source is deterministic.
- **Hotbar slots instead of enchantment checks.** Whether a pickaxe has Silk
  Touch is queryable, but that is exactly the part of the API that moves between
  versions. Configured slots are stabler and more predictable.
- **No trip back to a chest.** The shulker comes along. That saves the entire
  pathfinding problem.

## Building

```
./gradlew build
```

JDK 25 required; the jar lands in `build/libs/`.

## Languages

English and German, picked up from your Minecraft language setting. Adding
another language is a single file under `src/main/resources/assets/deepmine/lang/`
and no code change — pull requests welcome.

## License

MIT, see [LICENSE](LICENSE).

---

## Kurzfassung auf Deutsch

Clientseitiger Fabric-Mod für Minecraft 26.2 und 26.3. Gräbt einen 1 Block breiten und
4 Blöcke hohen Gang als quadratische Auswärtsspirale um einen Beacon, solange
die Eile II des Beacons reicht. Erz kommt mit Silk Touch heraus, Fackeln gehen
ins Dunkle, der Schutt in mitgeführte Shulker.

Beacon mit vollständiger Stufe-4-Pyramide setzen, Schacht nach oben freihalten,
**Eile als Primär- und Sekundäreffekt** wählen (sonst gibt es nur Eile I und der
Mod stoppt), mindestens 5 Blöcke weg stellen, Hotbar bestücken, `/deepmine start`.

Alle Befehle mit `/deepmine help`, alle Meldungen auf Deutsch. Einstellungen
werden in `config/deepmine.json` gespeichert, der laufende Auftrag in
`config/deepmine-job.json` — `/deepmine resume` funktioniert also auch nach
einem Neustart.

**Nur für Einzelspieler und Server, die das erlauben.** Der Mod steuert deine
Figur. Die meisten Mehrspieler-Server werten das als Cheat.
