# gd-edit

A save file editor for the game Grim Dawn.

**This is a fork of [Odie/gd-edit](https://github.com/Odie/gd-edit).** gd-edit was
written by Odie, and all of the original work is theirs. That project has not
been updated since 2021; this fork keeps it running against the current game and
adds to it. It keeps the original name because it is the same program — not to
imply any connection to, or endorsement by, the original author.

It was forked to fix the **Fangs of Asterkarn** expansion, which changed the save
format and broke the original editor, and has since gained real rolled item
stats, a seed search, ascended affixes and illusion support.

Distributed under the Eclipse Public License, the same licence as the original.
See [LICENSE](LICENSE).

## Support

Support is via the forum only, on either of the two gd-edit threads:

- [GD save file editor](https://forums.crateentertainment.com/t/tool-gd-save-file-editor/35817)
  -- the editor itself
- [GD save file editor character viewer](https://forums.crateentertainment.com/t/gd-save-file-editor-character-viewer/159875/2)
  -- the character sheet and the other `write` commands

Only bug fixes will be reviewed. Feature requests will not be considered.

**Issues here are turned off**, so the forum thread is the place to raise one.

You are also very welcome to fork it and fix it yourself. The source is here, the
save format work is documented in the commit history and in comments, and modern
AI coding tools are quite good at this kind of binary-format debugging — that is
largely how these fixes were found. Pull requests will not be monitored.

Back up your saves before using this.

## For users

Download the zip for your platform from the
[Releases](../../releases) page, unzip it anywhere, and run it:

| Platform | Run |
| --- | --- |
| macOS | double-click `gd-edit.app` |
| Windows | double-click `gd-edit.bat` |
| Linux | `./gd-edit.sh` |

**Java 17 or newer is required and is not bundled.** If you do not have it,
install a free build from [adoptium.net](https://adoptium.net/). The launchers
will tell you if it is missing rather than failing cryptically.

Everything the editor writes (`settings.edn`, `gd-edit.log`) stays in the folder
you unzipped, so uninstalling is just deleting the folder. On first run, point
it at your game with the `savedir` and `gamedir` commands — see the `README.txt`
inside the zip.

## Building

Requires a JDK (17+) and the [Clojure CLI](https://clojure.org/guides/install_clojure).

Build the uberjar only:

    $ clojure -T:build uber

Build the uberjar and a release zip for every platform:

    $ clojure -T:build dist

Archives land in `target/dist/`. Because no Java runtime is bundled, all three
platform archives can be built from a single machine — no cross-compilation and
no per-OS CI matrix is needed.

### Packaging layout

    packaging/
      common/
        gd-edit-launcher.sh   shared macOS + Linux launcher (Java detection)
        README.txt            end-user documentation, shipped in every zip
        THIRD-PARTY.txt       bundled dependency licenses
      macos/gd-edit.app/      .app bundle template
      windows/gd-edit.bat     Windows launcher

`@VERSION@` in `README.txt` and `Info.plist` is substituted at build time.

## Development

Run the tests:

    $ clojure -M:test -e "
      (def nss '[gd-edit.ascension-test gd-edit.character-stats-test
                 gd-edit.item-skills-test gd-edit.quest-layout-test
                 gd-edit.seed-search-test
                 gd-edit.sheet-test gd-edit.stash-tabs-test
                 gd-edit.text-dump-test])
      (apply require nss)
      (apply (requiring-resolve 'clojure.test/run-tests) nss)"

The namespaces are listed because some older files under `test/` predate this
and still want boot and midje, neither of which is a dependency any more, so a
blanket runner fails before reaching anything current.

Most tests need nothing but the source. The ones that check computed values
against the game itself have to be told where the game and the saves are, and
are skipped -- each saying what to set -- when they are not:

    $ GD_EDIT_TEST_GAMEDIR="/path/to/Grim Dawn" \
      GD_EDIT_TEST_SAVEDIR="$HOME/Documents/My Games/Grim Dawn/save/main" \
      clojure -M:test -e "..."

Those also need the item stat engine, so run `./scripts/fetch-item-stats.sh`
first.

### Terminal handling: jline 3 and jansi 2

Line editing comes from jline 3 and colour from jansi 2.4, and the two are
deliberately not wired together.

It used to be jline 2 and jansi 1.16, which failed badly on Apple Silicon.
jline 2 bundled its own copy of jansi's classes -- same package, older code, its
own native loader -- and that copy shadowed the real jansi on the classpath.
Neither had a native library for any arm64 platform, and jline's copy let the
failed load escape as an `Error`, so running from source died on startup on
every arm64 Mac. The built jar survived only because its merge order happened to
put the real jansi on top. jline 3 ships no such copy, and jansi 2.4 carries
natives for Mac, Linux and Windows on arm64 as well as x86.

**There is no `AnsiConsole.systemInstall()`.** Installing it wraps `System.out`,
and on a real terminal that wrapper and jline cannot both have their way --
measured under a pty, not reasoned about:

| order | result |
|---|---|
| jline, then jansi | a proper system terminal, but every colour code swallowed |
| jansi, then jline | colour survived, jline fell back to a dumb terminal, line editing gone |
| neither | a system terminal **and** colour |

So the wrapper is left out. An escape code written straight to stdout is what a
terminal wants anyway, and jline 3 handles the Windows console itself, which was
the only thing the wrapper was needed for.

The consequence is that a redirected run keeps the escape codes in the file,
exactly as every release so far has. `write character-text` is the way to get
clean text, and the character sheet *depends* on those codes surviving -- it
carries them into the page and turns them back into styling.

**Batch mode does not use jline at all.** A file needs no line editing, history
or terminal. Routing one through jline 3 fought it: `TerminalBuilder.streams`
alone still builds a system terminal that reads the real stdin, so batch mode sat
at the prompt waiting for a human. `set-input` reads the stream directly.

## License

Copyright © 2016 Jonathan Shieh

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).

gd-edit is an unofficial community tool. It is not affiliated with or endorsed
by Crate Entertainment, and distributes no game assets.
