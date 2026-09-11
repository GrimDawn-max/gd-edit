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

Support is via the forum page for gd-edit only. Only bug fixes will be reviewed.
Feature requests will not be considered.

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

    $ clojure -X:test

Note that running from source fails on Apple Silicon: the bundled jansi 1.16
ships an x86_64-only native library. Build and run the jar instead.

## License

Copyright © 2016 Jonathan Shieh

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).

gd-edit is an unofficial community tool. It is not affiliated with or endorsed
by Crate Entertainment, and distributes no game assets.
