# Forum release post — draft

Draft text for the Crate forum thread. Not published anywhere yet.

The post links to `/releases`, not to a version, so it never needs editing when a
new build goes up.

---

## New in this release

Four new commands, and a change to how support works.

Everything here computes from your own save and your own copy of the game
database. Nothing is downloaded and nothing is sent anywhere.

### `write character-sheet` — your character as a web page

One self-contained HTML file you can open in any browser, keep, or send.

- Every equipped item with the values it actually rolled, and the range each one
  could have landed in
- Attributes, Health, Energy, Offensive and Defensive Ability, Armor — with the
  game's own explanation of each behind a hover
- All ten resistances, with the overcap
- Both mastery trees at the level the game reads each skill at — points spent
  plus what gear adds
- Devotions, with each constellation's stars plotted on its own artwork and the
  skill each proc is bound to
- Buffs and auras currently running
- Hover anything — an item, a skill, a constellation, a stat — for the detail,
  phrased the way the game phrases it

```
write character-sheet ~/Desktop/mary.html
write character-sheet "C:\Users\You\Desktop\My Sheet.html"
write character-sheet ~/Desktop/            a folder: the character names the file
```

**The picture.** The panel beside the gear takes a character screenshot — drag one
onto it in the browser. A dropped picture stays in your browser rather than in the
file, so for a page you mean to send, name it on the command line and it is written
in:

```
write character-sheet ~/Desktop/mary.html ~/Desktop/shot.png
write character-sheet ~/Desktop/mary.html ~/Desktop/frames/
```

Name several — taken a rotation step apart, as the game's own arrow turns the
character — or a folder of them, and the sheet arrives already turning.

**`--no-art`** writes the same sheet with none of the game's pictures in it. Every
figure and every tooltip stays; the icons and constellation artwork go. A tenth the
size, and the one to post somewhere public.

**Sending one.** The file is self-contained: no copy of Grim Dawn, no gd-edit, no
internet. Every tooltip works for the recipient exactly as it does for you. It
fetches nothing — there is no network code in it at all, which you can confirm by
opening it in a text editor.

### `resists` — the ten resistances, computed

```
resists                    the ten resistances
resists all                and where each figure comes from
```

Resistances are not in a save file. The game works them out when it loads a
character, so gd-edit does the same work: your gear at its real rolled values, the
components, augments and set bonuses on it, the skills your items grant, the
devotion stars that are passive, the auras you have switched on, and the difficulty
penalty — then capped.

Because they are computed rather than read, a figure that disagrees with the
character screen is worth knowing about. `resists all` prints the parts each total
is made of, which is usually enough to see which one is at fault, and what the
figure would be without the cap.

### `write character-csv` and `write character-json` — the character as data

```
write character-csv ~/Desktop/mary.csv
write character-json ~/Desktop/mary.json
```

The CSV is one row per fact with a `section` column, so a spreadsheet can filter
it: attributes, resistances, every equipped item with what it rolled and the range
it came from, components and augments, skills and their levels, devotion
constellations.

The JSON is the same character shaped for other programs — the computed sheet, then
every item with its real values and ranges. It is self-contained: everything in it
is already resolved, so whatever reads it needs no copy of the game files. That is
the point of it. The game database is Crate's, and it is far too large to ship or
upload, while this file is a few tens of kilobytes of your own save with the numbers
already worked out.

### Item tooltips

A pass over what an item's description was leaving out, most of it found by
asking, for every field on every one of the game's 12,557 item records, whether
gd-edit could put it into words:

- A weapon now states its speed, and an item says when it is Soulbound
- A shield's block reads as the game reads it -- "34% Chance to block 975
  damage", with the recovery beneath it -- rather than as a resistance
- An item that modifies a skill shows what the modifier does, grouped under the
  skill it changes: the extra charge, the chance it is used, the cooldown it
  takes off another skill
- An item's line reads "Augmented Ascended Awakened Shield" the way the game
  says it
- Ranges no longer print as [20.000000298023224-30.000001192092896]

### Support

Support is via this thread only. Only bug fixes will be reviewed. Feature requests
will not be considered.

### Where the numbers come from

None of these values are stored in your save. It holds an item's identity and a
seed; the game database holds that item's ranges and the formulas. The real number
only exists once you combine them, which is what the game does every time it loads
your character. gd-edit does the same work, so what you get is your actual rolls
rather than what an item can roll.

That is also why Grim Dawn has to be installed for any of this: the figures, the
names and the artwork all come out of your own game folder at the moment you ask
for them.

### Notes

- There is no damage per second figure. The one the game prints covers weapon
  attacks against a single target, and leaves out skills, procs, damage over time
  and every conditional bonus, so it would be misleading.
- The figures have been checked against the game on my own characters.
- Back up your saves before using any save editor, this one included.

---

## Notes to myself, not for the post

The sheet carries Crate's artwork, read out of the player's own install. That is
fine on their own machine and is why gd-edit ships no sheets of its own: none of
that art is in the repository or the release, and each page is built from the
player's game folder at the moment they ask for it. `--no-art` exists for the case
where a page is going somewhere public. Note that even then the wording is still
Crate's — item names, stat phrasing, skill descriptions — so it is a smaller thing
to think about, not nothing.

Checked against the game on MaxFluffy, ColdFluffy and RedPriest — attributes,
combat stats and all ten resistances.

Before posting: push main so the docs site carries the four new commands. As of
2026-09-11 the site has only the support-statement change; `resists`,
`write character-csv`, `write character-json` and `write character-sheet` are
documented locally but not published.
