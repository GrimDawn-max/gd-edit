# Questions

The things people ask first. If your question is not here, `help` inside the app
lists every command, and `help <command>` explains one of them in detail.

## Getting started

### Where are my save files?

=== "Windows"

    ```
    Documents\My Games\Grim Dawn\save
    ```

=== "macOS / Linux"

    ```
    ~/Documents/My Games/Grim Dawn/save
    ```

Characters live in `save/main/_CharacterName`. The transfer stash and the
illusion collection sit in `save` itself.

### gd-edit cannot find my game or saves

Tell it where they are:

```
gamedir "C:\Program Files (x86)\Steam\steamapps\common\Grim Dawn"
savedir "C:\Users\You\Documents\My Games\Grim Dawn\save"
```

Quotes matter when the path contains spaces.

### Do I need to close the game first?

Yes. Grim Dawn keeps your character in memory and writes it when you save or
exit, so anything gd-edit writes while the game is running will be overwritten.

Close the game, make your changes, write, then start the game.

---

## Items

### How do I add an item to my inventory?

```
set inv/1/items "Mythical Amatok's Step"
```

`inv/1` is the first bag. Add a level cap if you want the version for a lower
level character:

```
set inv/1/items "Amatok's Step" 85
```

If several items share the name you will be asked which you meant. The
[item database](https://www.grimtools.com/db/) is the easiest way to check an
exact name first.

### How do I change a stack count?

```
show inv/1/items
set inv/1/items/0/stack-count 99
```

### Can I edit an item's stats directly?

No, and there is no field to edit. An item's record stores what it is made of —
base item, prefix, suffix — and a **seed**. The game computes every number from
those when it loads the item.

What you can do is choose a seed that rolls the stats you want. See
[`find-seed`](commands.md#find-seed).

### Can I optimise an item's seed?

Yes. [`find-seed`](commands.md#find-seed) searches the whole seed space in around
fifteen seconds, and either maximises every stat or finds rolls that clear
minimums you set.

!!! note "This used to say no"

    Earlier documentation said this was impractical, estimating about 1.65 months
    to try every seed. That was true of asking the game's own calculator about
    each one in turn. `find-seed` instead works out how a particular item rolls,
    once, and then runs a small loop over the seeds — which is what makes a full
    sweep practical.

### Why does one tool say 2 billion seeds and another say 4 billion?

Both are right, and they count different things.

There are **4,294,967,296** seed *values* — every number that fits in 32 bits —
producing **2,147,483,647** distinct *rolls*. A seed and that seed plus
2,147,483,647 put the random generator into the same state, so they roll
identically.

So a search that returns "10 matches" has usually found five results, each
listed twice. Either spelling works in the save.

### How do I put a component or augment on an item?

```
set inv/0/items/0/relic-name "Seal of Might"
set inv/0/items/0/augment-name records/items/enchants/b227a_enchant.dbr
```

[`find-seed`](commands.md#find-seed) offers both while creating an item, which is
usually easier.

### How do I pick an ascended bonus without knowing record paths?

Leave the value off and gd-edit lists them:

```
set inv/0/items/0/ascended-name
```

Each line says what the affix grants, because these records have no names of
their own. The list covers the loaded character's masteries; add `all` for
every affix the item can take. See
[ascended bonuses](commands.md#ascended-bonuses).

### Can I get the maximum roll on an ascended bonus?

There is no roll to maximise. Ascended affixes are fixed — no value in that data
rolls between a minimum and a maximum, and the item's seed does not affect them.
An affix that grants +3 to a skill grants exactly +3, every time.

To get a bigger number you choose a different affix, not a better roll. See
[ascended bonuses](commands.md#ascended-bonuses).

### How do I change an item's appearance?

The illusion applied to an item lives in its `transmute-name`:

```
set inv/0/items/0/transmute-name records/items/gearhead/d109_head.dbr
set inv/0/items/0/transmute-name ""
```

[`transmog list`](commands.md#transmog-list) shows the illusions unlocked on your
account. Picking one in game is easier, since you can see the result first.

---

## Characters

### How do I respec?

```
respec
```

### How do I change my character's name?

```
set character-name Bob
write
```

### How do I copy a character?

```
write NewName
```

Writes the loaded character out under a different name, leaving the original
alone.

### How do I revive a hardcore character?

```
set death-count 0
```

### How do I change faction alignment?

```
show faction-values
set faction-values/<index>/faction-value 50000
```

### How do I restore every shrine and rift gate?

```
set shrines/0 all
set teleporter-points/0 all
```

One list per difficulty, so repeat for each index. Adding is idempotent.

### Can I build a character from a GrimTools link?

```
make-char JVljdR7N --max-rolls
```

See [`make-char`](commands.md#make-char).

### Can I change quest choices?

No. Quest progress is stored separately from the character, in `quests.gdd`, and
gd-edit does not edit it.

---

## Things that go wrong

### gd-edit says it cannot read my save

The message will say the save is not damaged, and it is not — the game will
still load it. It means some field is not the size or type this version expects,
which usually happens after a game update changes the save format.

gd-edit stops rather than guessing, because writing back something it misread
would corrupt a file that is currently fine. Wait for a build that understands
the new format.

### My changes did not appear in the game

Two usual causes:

1. **You did not `write`.** Nothing is saved until you do.
2. **The game was running.** Grim Dawn writes your character on save or exit,
   overwriting whatever gd-edit put there. Close the game first.

### macOS says the app is damaged, or will not open

macOS blocks it because the build is not signed by an identified developer.
Signing requires a paid Apple Developer account, which this project does not
have. The app is not damaged and nothing is wrong with the download.

=== "macOS 15 (Sequoia) and newer"

    Double-click **gd-edit.app**. The warning offers only *Move to Trash* or
    *Done* — choose **Done**.

    Then open **System Settings → Privacy & Security**, scroll down to the
    Security section, and click **Open Anyway** beside the message about
    gd-edit.

    Control-clicking and choosing *Open* does **not** work on these versions.
    Apple removed that shortcut in macOS 15.

=== "macOS 14 (Sonoma) and older"

    Control-click (or right-click) **gd-edit.app**, choose **Open**, then
    confirm.

Either way it only needs doing once.

If you would rather use the terminal, this clears it outright and works on every
version:

```
xattr -dr com.apple.quarantine /path/to/gd-edit.app
```

Running the jar directly avoids the check altogether, since `java` is itself a
trusted program:

```
java -jar gd-edit.app/Contents/Java/gd-edit-standalone.jar
```

### Java is missing or too old

gd-edit needs Java 17 or newer. The launcher will say which version it found and
where. See [installing Java](index.md#java).

### make-char cannot reach GrimTools

Some connections are blocked by GrimTools' anti-bot protection, which a browser
can clear but gd-edit cannot. Open the build in your browser, save the page's
JSON, and hand the file to `make-char` instead of the link.

---

## Support

There is none. This is provided as-is, with no support and no bug reports, and
the repository does not accept issues.

It will be updated if a game patch or expansion breaks it. If something goes
wrong, restore a save backup.
