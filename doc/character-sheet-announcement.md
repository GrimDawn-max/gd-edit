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

---

## Before this can be posted

**There is no command for it yet.** The sheet is currently built by a pipeline of
six Clojure scripts and a Python renderer, run from the gd-edit source tree — see
`webviewer/README.md`. A reader of the post would have a packaged jar and none of
that, so the post promises something they cannot do.

The export commands that do exist are `write character-csv` and
`write character-json`; neither produces the page.

Two ways to close it:

- **Port the renderer into gd-edit** and add one command, say
  `write character-sheet <path>`, that writes the HTML directly. That makes the
  post true as written, and it also fixes the speed: the pipeline currently loads
  the game database six times over, once per script, which is why a sheet takes
  about seven minutes. In one process it would be well under one. The work is
  porting `render.py` — roughly 800 lines of Python that assemble the markup —
  into Clojure. Mechanical rather than difficult, but not small.
- **Export the data only**, say `write sheet-data <path>`, bundling everything
  the viewer needs into one JSON in a single database load, and leave the
  rendering to `render.py`. Much less work and it fixes the speed too, but the
  reader still needs Python, which is a poor ask on Windows.

Until one of those exists, the honest version of the post describes it as
something built from source rather than a feature of the release.
