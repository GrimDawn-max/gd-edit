# Commands

Type `help` at the prompt for this list inside the app, and `help <command>` for
the detail on any one of them. The in-app help is authoritative — this page
mirrors it.

Commands are grouped by what you are trying to do rather than alphabetically.

---

## Looking around

### show

Navigate the save file as though it were a directory tree. Partial names match,
so you rarely need to type a path in full.

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

### find / find all

Locate character data by name — items, equipment, skills, devotions.

```
find Amatok
find all Amatok             search every character you have
```

### db / q / qshow / qn

Explore the game's own database rather than your save.

```
db                          browse records
q Class=="ItemRelic"        query with conditions
qshow                       show the current page of results
qn                          next page
```

---

## Characters

### level

```
level 100
```

### class

```
class                       what the character is
class list                  every mastery the editor knows
class add Soldier
class remove Soldier
```

### respec

```
respec
```

Refunds skill points. Devotion points are separate.

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

Delete a character. There is no undo.

---

## Items

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

### find-seed

An item's stats are not stored in the save. What is stored is a **seed**, and
the game works the numbers out from it when the item loads. So asking for
particular stats means finding a seed that rolls them — which is what this does,
across all 2,147,483,647 of them, in about fifteen seconds.

```
find-seed Mythical Amatok's Step
find-seed Amatok's                 lists everything matching, to choose from
```

It offers two searches:

1. **Get every stat as high as it will go.** Nothing to enter.
2. **Set your own minimums.** You are shown each stat with its range and type a
   minimum for the ones that matter.

Then it builds the item, offering a blacksmith bonus and completion bonus
*before* the search — those roll from the seed — and a component, augment and
ascended bonus *after*, since those are fixed and cannot change which seed is
best.

### swap-variant

Swap an item for one of its variants.

```
swap-variant inv/0/items/0
```

### remove / rm

```
remove inv/0/items/3
```

### batch item

Create several copies of an item at once. Each copy is rolled separately, so you
get a pile of different items — unlike `find-seed`, where every copy shares one
seed and they are identical.

```
batch item 20 "Mythical Amatok's Step"
```

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

## The world

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

## Saving

### write

```
write                      save the character
write NewName              save as a copy under a new name
write stash                save the transfer stash
ws                         alias for write stash
```

Nothing reaches the game until you write.

### load

```
load                       choose a character
```

### write character-list

Export every character to a CSV file.

---

## Configuration

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

### update

```
update
```

Checks whether a newer release exists and gives you the link. It does not
download anything — a release is a zip you unzip yourself.

---

## Running commands from a file

### batch

```
batch path/to/commands.txt
batch character path/to/commands.txt
```

`batch character` runs the same file against every character you have — useful
for a change you want applied everywhere.

A batch file is one command per line, exactly as you would type them.

---

## Ascended bonuses

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

### Nothing about an ascended bonus is random

An ascended bonus is fixed. Every affix in the game states its numbers outright —
there is not one value anywhere in the ascended affix data that rolls between a
minimum and a maximum, and the item's seed has no bearing on it. Two items with
the same ascended affix have the same bonus, always.

So there is nothing to maximise here, which is why [`find-seed`](#find-seed) asks
about the ascended bonus *after* it searches: the choice cannot change which seed
is best.

### Which affixes an item can get

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
