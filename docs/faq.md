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

Yes. There are 4,294,967,296 seed values — every number that fits in 32 bits —
producing 2,147,483,647 distinct rolls.

[`find-seed`](commands.md#find-seed) searches the whole seed space in around
fifteen seconds per item, and either maximises every stat or finds rolls that
clear minimums you set. It takes around two to four minutes to do the same
across a full set of equipment using [`make-char`](commands.md#make-char) with
the `--max-rolls` flag.

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

### Items on a character I made with make-char show as unusable

The item sits in its slot with its requirement in red, and its bonuses are not
counted — so the character's attributes, combat stats and resistances all read
lower than they should, and lower than gd-edit shows them.

This is about how the character was assembled, not about the build. A build
reaches a heavy item's attribute requirement through the rest of its gear: in
play the gear went on one piece at a time and every step was legal, so the game
never had to work anything out. `make-char` puts all fourteen pieces on at once,
and the game checks equipment when it loads a character it did not equip itself.
It cannot count a bonus from an item it has not accepted yet, and it has no
legal order to work back to, so some items are refused.

**To settle it**, raise the attribute the item asks for, load the character
once, and set it back:

```
set physique 1000
write
```

Load the character in the game, then quit. Now put it back:

```
set physique 178          the value the build actually uses
write
```

The game keeps the gear from then on — acceptance is sticky, so the temporary
figure does not need to stay. Anything you generated in between, a character
sheet especially, describes the bumped character and should be regenerated.

Two things worth knowing:

- **It does not happen to every build.** Of six characters imported from
  published builds, two were affected — one with seven items refused and one
  with a single item. Which items a build can bootstrap depends on what the
  character has before any gear counts, including what its skills and devotions
  give, and gd-edit cannot work it out in advance. `make-char` says the
  situation may arise; it does not claim to know which items.
- **gd-edit's own figures are right.** It computes from the gear as equipped,
  which is the settled state the game reaches once the items are accepted. On a
  character the game equipped itself this never arises, which is why a played
  character's sheet matches the game exactly.

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

You will see:

```
grimtools.com refused the request (HTTP 403).
```

GrimTools sits behind Cloudflare, which shows an anti-bot challenge to some
visitors. Clearing it requires running javascript, so a browser passes and
gd-edit cannot — and the decision is made per visitor, so there is no request
gd-edit could make instead that would get through.

The way round it is to let your browser fetch the build and hand the result to
gd-edit. Four steps:

**1. Find the build id.** It is the part of the calculator link after `/calc/`:

```
https://www.grimtools.com/calc/JVljdR7N
                                ^^^^^^^^ this
```

**2. Open the build *data* in your browser** — a different address from the
calculator page:

```
https://www.grimtools.com/get_build_data.php?id=JVljdR7N
```

gd-edit prints this exact address when it hits the error, so you can copy it
from there rather than building it yourself.

You should see a wall of plain text starting `{"data": {"bio": {...` — that is
the build data. It is not meant to look like the build.

**3. Save that page.** `Cmd`+`S` on macOS, `Ctrl`+`S` on Windows and Linux.
Anywhere you like, under any name. The extension does not matter.

**4. Give the saved file to `make-char`** in place of the link:

```
make-char ~/Downloads/get_build_data.php.json
```

!!! warning "Do not save the calculator page"

    The commonest mistake is saving `.../calc/JVljdR7N` — the page you were
    looking at. That is the web page, not the data behind it, and `make-char`
    cannot read it. If you do, gd-edit says so and repeats the right address.

---

## Support

Support is via the [forum page for gd-edit](https://forums.crateentertainment.com/t/tool-gd-save-file-editor/35817) only.
Only bug fixes will be reviewed. Feature requests will not be considered.

The repository does not accept issues, so the forum thread is the place to raise
one.

It will be updated if a game patch or expansion breaks it. If something goes
wrong, restore a save backup.
