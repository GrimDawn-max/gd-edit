# Commands

Type `help` at the prompt for this list inside the app, and `help <command>` for
the detail on any one of them. The in-app help is authoritative — this page
mirrors it.

---

## Basic commands

### show / ls

Navigate the save file as though it were a directory tree. Partial names match,
so you rarely need to type a path in full. `ls` is the same command.

```
show                        the top level
show character-name
show inv/1/items            everything in the second bag
show inv/0/items/3          one item, in detail
show equipment              what the character is wearing
```

Showing an item gives its **real rolled values**, with the range each came from,
matching what the game's tooltip says:

```
+89% Cold Damage [67-100]
```

Along with everything attached to it — component, completion bonus, augment,
[ascended bonus](#ascended-bonuses), and the item's set if it belongs to one.

### set

Change a field, or create an item.

```
set character-name Bob
set inv/1/items "Mythical Amatok's Step"
set inv/1/items "Amatok's Step" 85          cap the level
set inv/0/items/0/stack-count 99
```

Some fields understand names as well as record paths — a component can be named
directly:

```
set inv/0/items/0/relic-name "Seal of Might"
```

### find / find all

Locate character data by name — items, equipment, skills, devotions.

```
find Amatok
find all Amatok             search every character you have
```

### swap-variant

Swap an item for one of its variants.

```
swap-variant inv/0/items/0
```

### write

```
write                      save the character
write NewName              save as a copy under a new name
write stash                save the transfer stash
ws                         alias for write stash
```

Nothing reaches the game until you write.

### ws

Alias for `write stash`.

### load

```
load                       choose a character
```

### help

```
help                       every command, with a one-line description
help set                   the detail on one command
```

### update

```
update
```

Checks whether a newer release exists and gives you the link. It does not
download anything — a release is a zip you unzip yourself. gd-edit also tells you
at startup when there is one.

### exit

Closes the program. Anything you have not [written](#write) is discarded.

---

## Convenience commands

### level

```
level 100
```

### respec

```
respec                     everything
respec attributes          refund attribute points
respec skills              remove masteries and skills, refund the points
respec devotions           remove devotions, refund the points
```

With no argument it does all three.

There is also `respec hard`, which does the same as the default but removes the
devotion entries from the character outright instead of leaving them in place
disabled. The ordinary form is the one you want; `hard` exists for a character
the normal respec leaves in a state the game will not accept.

### shrine list / gate list

```
shrine list                every shrine the editor knows
gate list                  every rift gate
```

To restore them on your character, `set` against the character's own lists. A
name works, and so does the word **all**:

```
set shrines/0 all
set teleporter-points/0 all
```

There is one list per difficulty, so repeat for each index you care about.
Adding is idempotent — only missing entries are appended.

---

## Configuration commands

### gamedir / savedir

```
gamedir "C:\Program Files (x86)\Steam\steamapps\common\Grim Dawn"
savedir "C:\Users\You\Documents\My Games\Grim Dawn\save"
gamedir clear
savedir clear
```

Quotes matter when a path contains spaces.

### mod

```
mod                        the mod currently selected
mod pick                   choose an installed mod
mod clear                  go back to the base game
```

### log

Set how much gd-edit writes to `gd-edit.log`.

```
log                        the level in force
log debug                  more detail, for chasing a problem
log clear                  back to the default
```

Levels are `trace`, `debug`, `info`, `warn`, `error` and `fatal`. The default is
`info`.

### diag

Check the setup: your Java version, whether the game directory was found, and
whether the database and resource files gd-edit needs are where it expects.

```
diag
```

```
✔ JVM version: 21.0.10+7
✔ Game directory exists
✔ File exists: .../database/database.arz
✔ File exists: .../resources/Text_EN.arc
✔ File exists: .../resources/Items.arc

Looks good! The editor should be ready to go!
```

Worth running first if item creation or database queries are not working — a
missing game directory is the usual cause.

---

## Class manipulation

### class

```
class                       what the character is
class list                  every mastery the editor knows
class add Soldier
class remove Soldier
```

---

## Skills and devotions

There is no `skill` command. Skills and devotions are entries in the character's
`skills` collection, edited with [`find`](#find-find-all) and [`set`](#set) like
anything else.

### Changing a skill you already have

Find it by name, then set its level:

```
find Wendigo
```

```
Wendigo Spirit: inventory-sacks/0/inventory-items/48
Wendigo Totem: skills/30
```

```
set skills/30/level 16
```

### Telling skills and devotions apart

Both live in the same collection. `devotion-level` is what separates them:

| | `devotion-level` | taken looks like |
|---|---|---|
| mastery skill | `0` | `level` is the points invested |
| devotion node | `1` | `level 1`, `enabled true` |

An untaken devotion node still appears, sitting at `level 0` with
`enabled false` — that is also how [`respec`](#respec) leaves them, disabled
rather than removed.

### Points

The pools are ordinary top-level fields:

```
set skill-points 50
set devotion-points 55
```

`attribute-points` works the same way. `total-devotion-points-unlocked` records
how many the character has earned in total, as opposed to how many are unspent.

Two nearby fields are bookkeeping rather than pools, and are better left alone:

`skill-points-reclaimed` and `devotion-points-reclaimed`
:   How many points the character has refunded over its life. The game tracks
    these; gd-edit only reads and writes them.

`devotion-shrines-unlocked`
:   A **statistic**, not a pool. It sits with `hero-kills`, `relics-crafted` and
    `lore-notes-collected` in the character's tally of things done, and changing
    it grants nothing. Restoring shrines on a character is
    [`set shrines/0 all`](#shrine-list-gate-list).

### Spend them in the game

!!! tip "The easier route"

    Give yourself the points with `set`, [`write`](#write), then spend them at a
    trainer and in the devotion screen.

Three reasons that is better than editing the skills directly:

**You can only edit what is already there.** A mastery skill the character has
never put a point into has no entry at all, so there is nothing for `set` to
change. Creating one needs [`make-char`](#make-char); there is no command for it.

**Nothing is checked.** gd-edit writes what you tell it. It does not verify you
have the points, or that a devotion's affinity requirements are met.

**Affinity is not stored.** The save has no affinity field — the game works it
out from the nodes you hold. So a hand-assembled set of devotions can be one the
game's own rules would never have allowed, and gd-edit has no way to warn you.

Spending in the game avoids all three: the rules are enforced as you click, and
the constellation screen shows affinity accumulating as you go.

---

## Item management commands

### remove / rm

Take an item out of a collection. `rm` is the same command.

```
remove inv/0/items/3        the fourth item in the first bag
rm inv/0/items/3            the same thing
```

A trailing `*` empties the collection:

```
remove inv/1/items/*
```

```
Removed 29 items from "inventory-sacks/1/inventory-items"
```

Like every other change, this happens in memory — nothing reaches the save until
you [`write`](#write).

---

## Character creation

### make-char

Build a character from a [GrimTools](https://www.grimtools.com/calc/) link.

```
make-char https://www.grimtools.com/calc/JVljdR7N
make-char JVljdR7N
make-char JVljdR7N --max-rolls
```

Everything GrimTools records about the equipment comes across: items, prefixes
and suffixes, components, augments, relic completion bonuses and
[ascended bonuses](#ascended-bonuses).

If GrimTools refuses the request — some connections are shown a Cloudflare
challenge only a browser can clear — you can save the build from your browser
and pass the file instead:

```
make-char ~/Downloads/get_build_data.php.json
```

The [FAQ](faq.md#make-char-cannot-reach-grimtools) walks through it. Note that
the file to save is the build *data*, not the calculator page.

### Rift gates

`make-char` unlocks every rift gate on all three difficulties, and says so:

```
Setting all 72 rift gates...
```

Without this a built character inherits only the template's gates, which do not
include the Fangs of Asterkarn ones — the expansion's map is visible but none of
it can be reached.

It matters for more than travel. Grim Dawn sets your respawn point when you
arrive somewhere, so a character with no gate to that town also has no way to
change where it starts: it stays wherever the template last stood, which is the
Forgotten Gods town. Unlocking the gates leaves the choice with you — travel to
Kurnhold, or anywhere else, once and the game starts you there from then on.

Nothing else about the character's progress is touched. Quests are not completed
and shrines are not restored; `set shrines/0 all` does the latter if you want it.

`--max-rolls` gives every equipped item the best seed it can have. Without it,
each item gets a random seed — a legitimate item, but an average one. Each item
costs a full search, so a set of equipment takes a few minutes.

!!! note "Why some items come out a stat short"

    A seed decides *all* of an item's stats at once, so an item with every stat
    at its maximum frequently does not exist at all. Expect most items to be
    perfect and some to fall one stat short — that is the genuine ceiling, proven
    by searching every seed, not a near miss.

    To choose *which* stat gives way, rebuild that one item with
    [`find-seed`](#find-seed) and set a minimum on the stat you care about.

### delete

Delete a character. With no argument it lists them and asks which:

```
delete
```

```
Please choose a character to load:
1) AscendMeNow (local save)
2) Mary (local save)
3) RedPriest (local save)

> 2
Moving character to trash:
    /Users/you/Documents/My Games/Grim Dawn/save/main/_Mary
Deleted.
```

Or name the character, or give the path to its save folder or `player.gdc`:

```
delete Mary
delete "/Users/you/Documents/My Games/Grim Dawn/save/main/_Mary"
```

The character's whole folder goes to the **system trash**, so it can be restored
from there if you did not mean it. gd-edit itself has no undo.

If the name matches more than one character — the same name in a local save and
a cloud save, say — it lists them and does nothing, so you can name the path of
the one you meant.

### write character-list

Export every character to a CSV file.

### write character-csv

Export the **loaded** character to a CSV file, for a spreadsheet.

```
write character-csv ~/Desktop/mary.csv
write character-csv "C:\Users\You\Desktop\mary.csv"
```

One row per fact, with a `section` column, so a spreadsheet can filter it. It
covers the attributes, the resistances, every equipped item with what it actually
rolled and the range each stat came from, the components and augments on them, the
skills taken and at what level, and the devotion constellations.

The path is required, so the file goes where you meant it to rather than into
whatever folder the app happened to start in. Quote it if it contains spaces.

### write character-json

The same character, shaped for other programs rather than for a spreadsheet.

```
write character-json ~/Desktop/mary.json
write character-json "C:\Users\You\Desktop\mary.json"
```

It carries the computed sheet — attributes, health, energy, offensive and defensive
ability, armour and resistances — and then every equipped item with its real rolled
values and the range each came from.

It is **self-contained**. Everything in it is already resolved, so whatever reads it
needs no copy of the game files. That is the point: the game database is 173MB of
Crate's data that a website cannot ship and you cannot reasonably upload, while this
file is a few tens of kilobytes of your own save with the numbers already worked out.

### write character-sheet

The character as the game shows it, as a web page.

```
write character-sheet ~/Desktop/mary.html
write character-sheet "C:\Users\You\Desktop\My Sheet.html"
write character-sheet ~/Desktop/            a directory: the character names the file
```

Equipment with its full tooltips, attributes and combat stats, resistances, every
skill at the level it actually reaches, the devotion map, and the buffs that are
running. Hover anything on the page for the detail behind it — an item's complete
stat block, where a combat figure comes from, what a skill does at its current rank.

The page is **one file and needs nothing else**: no game, no gd-edit, no internet.
Every picture is embedded in it and nothing is ever fetched, so a browser needs
nothing turned on, and the file can be kept, opened years later, or sent to someone
who has neither gd-edit nor Grim Dawn.

The same figures the rest of gd-edit computes go onto the page, so it agrees with
`show`, `resists` and the item summaries — the save stores seeds rather than stats,
and these are the values resolved against your own game database.

That artwork is Crate's, read out of your own install at the moment the sheet is
written. It is why gd-edit ships no sheets of its own, and worth a thought before
posting one publicly.

**The picture.** The panel beside the gear takes a character screenshot. Drag one
onto it in the browser and it stays in that browser, against that character. Name a
picture on the command line instead and it is written into the file itself, which is
what a page meant to be sent somewhere wants:

```
write character-sheet ~/Desktop/mary.html ~/Desktop/screenshot.png
```

PNG, JPEG or WebP. Drop several shots taken a rotation step apart — the game's own
arrow turns the character — and the panel turns with them.

---

## Resistances

### resists

Show the loaded character's resistances as the game shows them.

```
resists                    the ten resistances
resists all                and where each figure comes from
```

Resistances are not stored in a save — the game works them out when it loads a
character. So these are computed: from your gear at its real rolled values, the
components, augments and set bonuses on it, the skills your items grant, the
devotion stars that are passive, the auras you have switched on, and the difficulty
penalty. Green is the real figure; yellow means it is capped, and `resists all`
shows what it would be without the cap.

Because they are computed rather than read, a figure that disagrees with the
character sheet is worth knowing about. `resists all` prints the parts each total is
made of, which is usually enough to see which one is at fault.

---

## Illusions

### transmog list

Show the illusions unlocked on this account — what the Loyalist packs and drops
entitle you to. Recorded per account rather than per character.

```
transmog list              everything, grouped by slot
transmog list crest        only those matching "crest"
```

This only reads. The game has no way to show you the collection, which is why
the command exists; choosing an illusion is better done in game, where you can
see the result first.

**Applying** one is a separate thing, and `set` already does it. The illusion on
an item lives in its `transmute-name`:

```
set inv/0/items/0/transmute-name records/items/gearhead/d109_head.dbr
set inv/0/items/0/transmute-name ""              remove it
```

gd-edit does not check that you own an illusion before applying it. The game may
not honour one you are not entitled to, and `transmog list` is how to see what
that is.

---

## Item rolls

### find-seed

An item's stats are not stored in the save. What is stored is a **seed**, and
the game works the numbers out from it when the item loads. So asking for
particular stats means finding a seed that rolls them — which is what this does,
across all 2,147,483,647 of them, in about fifteen seconds.

```
find-seed Mythical Amatok's Step
find-seed Amatok's                 lists everything matching, to choose from
```

It offers a **prefix** and a **suffix** while building, alongside the blacksmith
and completion bonuses, and searches with them in place.

Choose them here rather than adding them afterwards. An affix consumes draws of
its own and shifts every stat after it, so a seed chosen for the unaffixed item
no longer suits it once an affix is set on it — on one pendant, every stat at
maximum became one of six that way.

It offers two searches:

1. **Get every stat as high as it will go.** Nothing to enter.
2. **Set your own minimums.** You are shown each stat with its range and type a
   minimum for the ones that matter.

Then it builds the item, offering a blacksmith bonus and completion bonus
*before* the search — those roll from the seed — and a component, augment and
ascended bonus *after*, since those are fixed and cannot change which seed is
best.

### Pet bonuses

Some items carry a **Bonus to All Pets** — a second block of stats that applies
to your pets rather than to you.

```
Bonus to All Pets
  +8%  Health                   [7-9]
  +50% to All Damage            [44-66]
  44%  Aether Resistance        [32-48]
  42%  Chaos Resistance         [32-48]
  24%  Reduced Freeze Duration  [16-24]
```

**There is nothing to set.** Whether an item has one is a property of the item
itself, so you cannot add a pet bonus to an item that lacks one, or swap the one
it has. Around 1,200 records carry one; the rest never will.

What you can control is **how well it rolls**, because it rolls from the item's
seed — on a stream of its own, uncorrelated with the item's ordinary stats.

That last part decides which tool to use:

[`make-char --max-rolls`](#make-char)
:   Scores every pet bonus the item carries — its own and any its prefix brings —
    alongside all its other stats. Nothing to do.

[`find-seed`](#find-seed), **option 1** — *get each stat as high as it will go*
:   Also scores it. This is the one you want for a pet item.

[`find-seed`](#find-seed), **option 2** — *set your own minimums*
:   Scores it too, but later and over less ground. The search itself filters on
    your minimums only; the pet bonus is then used to rank the matches it
    gathered — a pool of up to a thousand — rather than being an objective
    across the whole seed space.

!!! note "Affixes are searched too"

    A pet bonus can come from the item itself (450 records) or from its prefix
    (326 records). Both are optimised, along with the prefix's and suffix's
    ordinary stats.

    This was not always so: until 0.2.501 the search was fitted to the base item
    with its affixes ignored, so on an affixed item it optimised only part of
    what you were holding. If you built a character with an earlier version,
    rerunning `make-char --max-rolls` will improve its affixed items.

!!! note "Why it has to be scored deliberately"

    Because the two streams are uncorrelated, a seed chosen only for the item's
    own stats leaves the pet bonus to chance — and chance does badly across five
    stats at once. On *Mogdrogen's Ardor* the best-for-the-item seed scores 0.56
    on pets; searching both together finds seeds that max both.

So for a pet item option 1 does the more thorough job, since it optimises the
pet bonus across every seed rather than across the matches a filter happened to
collect. Option 2 is still the right choice when you need to force one of the
item's own stats — it will not ignore the pet bonus, it just has less to choose
from.

### Ascended bonuses

Fangs of Asterkarn added ascended affixes, applied at the altar in Kurnhold.
gd-edit reads them, shows them, and can set them.

Leave the value off and it lists what the item can take:

```
set inv/0/items/0/ascended-name
```

```
Currently: +3 to Wasting

Occultist
   1. +2 to Blood Burst   (modifies Dreeg's Evil Eye)
   2. +4 to Curse of Frailty
   3. modifier to Maiven's Sphere -- Reduced Crowd Control Duration 5
Shaman
   4. +3 to Wendigo Totem
Any mastery
   5. Offensive Ability 20, Crit Damage 4

Showing this character's masteries. Add "all" to see every affix the item can take.
Which? [1-5, blank to cancel]:
```

These records have no names. Ordinary prefixes and suffixes are called things
like *of the Boar*, but not one of the 993 ascended affixes carries a name, so
each line says what the affix **grants** instead.

The list is cut to the loaded character's masteries, since that is what the altar
would offer them — for a common item that is about 24 entries rather than 88.
`set <path>/ascended-name all` shows every affix the item can legally take.

A record path still works if you have one:

```
set inv/0/items/0/ascended-name records/items/lootaffixes/ascended/mastery/playerclass06/b306c.dbr
```

Either way it refuses an affix the altar could not have produced on that item —
the check comes from the game's own tables, based on the item's rarity and
category.

#### Nothing about an ascended bonus is random

An ascended bonus is fixed. Every affix in the game states its numbers outright —
there is not one value anywhere in the ascended affix data that rolls between a
minimum and a maximum, and the item's seed has no bearing on it. Two items with
the same ascended affix have the same bonus, always.

So there is nothing to maximise here, which is why [`find-seed`](#find-seed) asks
about the ascended bonus *after* it searches: the choice cannot change which seed
is best.

#### Which affixes an item can get

Only **Common** and **Magic** items can receive the mastery affixes that add
skill levels. Epic, Rare and Legendary items draw from a smaller pool granting
the skill modifier alone — no skill levels at all.

Where an affix does add skill levels, the amount is a property of that affix, not
a roll:

| grants | affixes |
|---|---|
| +2 to a skill | 310 |
| +3 to a skill | 161 |
| +4 to a skill | 168 |
| +6 to a skill | 1 — Soldier's *Scars of Battle*, the only one in the game |

Picking a different affix is how you get a different number. The same affix always
gives the same amount.

---

## Batch commands

Run a file of commands, one per line, exactly as you would type them.

### batch

```
batch path/to/commands.txt
batch character path/to/commands.txt
```

`batch character` runs the same file against every character you have — useful
for a change you want applied everywhere.

### batch item

Fill a slot with copies of an item.

```
batch item inv/1/items "Mythical Amatok's Step"
batch item tra/0/items "Mythical Hammerfall Girdle"
batch item inv/1/items "Amatok's Step" 85          cap the level
```

```
Placed 16 items into inventory-sacks/1/inventory-items
```

The first argument is **where**, not how many: it creates as many as the slot
holds. Each copy is rolled separately, so you get a pile of different items —
unlike [`find-seed`](#find-seed), where every copy shares one seed and they are
identical.

### From the command line

gd-edit can run a batch file without you sitting at the prompt:

```
gd-edit.exe -f "<path to player.gdc>" -b "<path to command file>"
```

Redirect the output to keep a record of what happened:

```
gd-edit.exe -f "<path to player.gdc>" -b "<path to command file>" > output.txt
```

`-f` names the save to load and `-b` the file of commands to run. On macOS and
Linux the launcher takes the same flags: `./gd-edit.sh -f ... -b ...`

These are gd-edit's only command-line options, and `-h` lists them:

```
gd-edit.exe -h
```

```
The valid options are:
  -f, --file SAVE_FILE_PATH    Save file to load on start
  -b, --batch BATCH_FILE_PATH  Batch file to run
  -h, --help                   Show this help text
```

`-h` is not specific to batch — it prints this and exits, whatever else you pass.
Everything else gd-edit does is driven from the prompt, not from flags.

---

## Database exploration

These read the game's own database rather than your save.

### db / q / qshow / qn

Explore the game's own database rather than your save.

```
db                          browse records
q Class=="ItemRelic"        query with conditions
qshow                       show the current page of results
qn                          next page
```
