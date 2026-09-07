# Cutting a release

Not part of the published documentation site — this is a note for whoever builds.

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
