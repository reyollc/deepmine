# Changelog

## Unreleased

- Ore next to a finished column is no longer mined. It sat above the ceiling,
  below the floor, beyond the last ring or in the core around the pyramid, and
  mining it below the floor left holes. Ore inside the area is still mined, with
  Silk Touch, when the spiral reaches it.

## 1.11.1

- Homepage, source and issue tracker links in the mod metadata, so the in-game
  mod list links to the Modrinth page and the repository.

## 1.11.0

- Supports Minecraft 26.3 alongside 26.2, from the same jar.
- `LivingEntity.swing` gained two parameters in 26.3 and the old signature is
  gone, so the arm animation now goes through a small compatibility helper that
  picks the right one at load time. Everything else is identical between the two
  versions; that is checked by compiling against both.

## 1.10.0

- Mass-storage boxes now only accept full stacks; a started stack stays in the
  inventory until it is full. Default on for `only` boxes, off for `except`/`all`
  boxes, switchable with `/deepmine box <slot> fullstacks on|off`.
- `/deepmine store` summary after each cycle shows how many stacks and items
  ended up in the shulker.

## 1.9.0

- Shulker boxes are distributed across the configured slots by their actual
  contents, read from the item without placing it. Spare boxes anywhere in the
  main inventory are swapped in automatically as boxes fill up, and part-filled
  boxes are topped up before an empty one is opened.
- A filled shulker is swapped back into its own slot instead of landing in the
  first free one, and can no longer be left on the floor.
- A box that cannot take anything more is parked in place; the other box runs
  first and the parked one is collected afterwards. Nothing is ever thrown away
  and no foreign items are put into a sorted box.

## 1.8.0

- English and German localisation, picked up from the Minecraft language
  setting. Item and block names come from Minecraft's own translations.
- Colour-coded chat output: white for information, green for success, gold for
  attention, red for aborts, with positions and numbers highlighted.
- `/deepmine help` and `/deepmine config`.

## 1.7.0

- The running job is saved to `config/deepmine-job.json`, so `/deepmine resume`
  works after a restart or a server change. The dimension is checked and the
  position of the next cell is reported.
- `/deepmine forget` discards the saved job.

## 1.6.0

- Fighting now takes priority over every abort condition, including low health —
  standing still while a zombie hits you is always worse.
- Enemies are engaged when they come close instead of after the first hit, are
  approached step by step on verified ground, and creepers are never approached.
- Low health with no enemy around waits for regeneration instead of stopping.

## 1.5.0

- Falling gravel and sand are waited out, re-queued, and flushed through with
  the torch trick instead of being mined block by block.

## 1.4.0

- The box that frees the most stacks is chosen, instead of the first one that
  would take anything.
- Roughly 13-15% less idle time between cells.

## 1.3.0

- All settings are saved to `config/deepmine.json` and loaded at startup.
- Automatic eating from a food slot, before the hunger abort triggers.
- Fixed: configured hotbar slots were not actually protected when storing, so
  tools could be put into a shulker.

## 1.2.0

- Gravel is mined with Silk Touch by default, because Fortune III turns it into
  nothing but flint. Extend the list with `/deepmine silk <block>`.

## 1.1.0

- Holes in the floor are closed with blocks from a configured slot before the
  next step, instead of walking into them.
- Fixed: the lava check only ran while actually mining, so an already-open
  column — a natural cave — was never checked.

## 1.0.0

First working build for Minecraft 26.2.
