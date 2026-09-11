# Character sheet viewer — announcement draft

Draft text for the forum post. Not published anywhere yet.

---

## Character sheet viewer

gd-edit can now render a character as a web page: one self-contained HTML file
you can open in any browser, keep, screenshot or send.

### What it shows

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

### Where the numbers come from

The values aren't stored anywhere. Your save holds an item's identity and a
seed; the game database holds that item's ranges and the formulas. The real
number only exists once you combine them, which is what the game does every time
it loads your character. gd-edit does the same work, so the sheet shows your
actual rolls rather than what an item can roll.

### Making one

Load a character in gd-edit and give it somewhere to write:

```
write character-sheet ~/Desktop/mary.html
write character-sheet "C:\Users\You\Desktop\My Sheet.html"
write character-sheet ~/Desktop/            a folder: the character names the file
```

A picture named after the path is written into the file itself:

```
write character-sheet ~/Desktop/mary.html ~/Desktop/screenshot.png
```

`help write character-sheet` has the rest.

### What you need

- Grim Dawn installed — the sheet reads the item database, icons and textures
  from your game folder
- gd-edit
- The output needs nothing at all: no server, no internet, everything embedded
  in the file

### Notes

- There is no damage per second. The figure the game prints only covers weapon
  attacks against single targets, and leaves out skills, procs, damage over time
  and conditional bonuses, so it would be misleading.
- The portrait is yours to supply — drag a screenshot onto the panel. Drop
  several taken a rotation step apart and the character turns.
- The figures have been checked against the game on my own characters.

### Sharing one

The file is self-contained, so you can send it to anyone: they need no copy of
Grim Dawn, no gd-edit and no internet connection. Everything works for them
exactly as it does for you — every tooltip, every hover, the lot. A portrait you
dragged on stays in your browser rather than in the file, so for a page you mean
to send, name the picture on the command line and it is written in.

It fetches nothing. There is no network code in it at all — no downloads, no
calls out, nothing to a server. You can confirm that by opening it in a text
editor.

---

## Notes to myself, not for the post

The sheet carries Crate's artwork, read out of the player's own install. That is
fine on their own machine and is why gd-edit ships no sheets of its own: none of
that art is in the repository or the release, and each page is built from the
player's game folder at the moment they ask for it. Worth keeping in mind before
posting a page publicly, as against sending one to someone.

The figures have been checked against the game on MaxFluffy, ColdFluffy and
RedPriest — attributes, combat stats and all ten resistances.
