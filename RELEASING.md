# Cutting a release

Not part of the published documentation site — this is a note for whoever builds.

## The item stat engine

The classes that turn an item seed into its real rolled values are marius00's
Grim Dawn Item Stats. They are **not committed** -- `java-src/` is gitignored --
so a fresh clone does not have them:

```
./scripts/fetch-item-stats.sh
```

That fetches the four source files at a pinned commit into `java-src/`, which
the build compiles in. The script tries `GrimDawn-max/GrimDawnItemStats` first
and `marius00/GrimDawnItemStats` second, so a build survives the upstream
repository being renamed, rewritten or taken down.

**Do not skip this before a release.** Without the engine gd-edit still builds
and runs -- `item-stats` resolves it reflectively and every entry point returns
nil -- and the app quietly falls back to showing unrolled item ranges instead of
real values. Nothing fails, so a release built without it looks fine until
someone reads an item.

The pinned commit lives at the top of the script. Bump it deliberately and re-run
the tests: the seed search is fitted to this engine's draw order, so a change
there can move every rolled value in the app.

## Build

```
clojure -T:build dist
```

That builds the uberjar once and packages it three ways, leaving
`gd-edit-<version>-windows.zip`, `-macos.zip` and `-linux.zip` in `dist/`.
Each zip holds the same jar plus the launcher for that platform.

Version comes from `build.edn`, generated during the build from git.

## Publish

```
gh release create v<version> dist/*.zip --title "v<version>" --notes-file notes.md
```

The app checks `api.github.com/repos/GrimDawn-max/gd-edit/releases/latest` on
startup and compares the tag against its own version, so the tag must be
`v<major>.<minor>.<patch>` and must sort correctly. It only reports and links —
nothing downloads or installs itself.

## What was removed

The upstream project published a single executable to the author's Dropbox and
had the app download and swap itself in place. Those endpoints
(`tiny.cc/gdedexe` and friends) are still live and still serve his 2021 build,
so they were removed rather than left pointing somewhere harmful.
