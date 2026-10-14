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

## The whats_new entry must carry the version the build will produce

`whats_new.clj` holds a compiled-in list, and the first run after an upgrade
prints the newest entry -- but only if it is newer than the version that ran
last. The version comes from `git rev-list --count HEAD`, so **every commit
changes it**, including the commit that adds the entry.

Get this wrong and nothing fails: the release builds, publishes and runs, and
the notice is simply silent. It happened on 0.2.581, where the entry said
0.2.572 because that was the count when the text was written.

So: write the entry, commit it, then

```
git rev-list --count HEAD
```

and make the entry's `:version` that number. If it needs changing, `git commit
--amend` rather than making another commit -- a new commit moves the count
again.

## Archive the zips

`target/` is deleted at the start of every build, so anything left there is
gone on the next one. Copy the three zips to `release-archive/` and append
their checksums, which is also where to send anyone who wants a copy without
adding to the GitHub download counts:

```
cp target/dist/gd-edit-<version>-*.zip ../release-archive/
(cd ../release-archive && shasum -a 256 gd-edit-<version>-*.zip >> SHA256SUMS.txt)
```

## Publish

```
gh release create v<version> target/dist/*.zip --repo GrimDawn-max/gd-edit \
  --title "v<version>" --notes-file notes.md
```

`--repo` is not optional: this clone has an `upstream` remote pointing at
Odie/gd-edit, and without it `gh` resolves there and fails with a 404.

Note that `gh release download` does **not** add to an asset's download count --
it goes through the authenticated API rather than the public URL. Verifying a
release therefore costs nothing, and the counts stay a measure of real users.

The app checks `api.github.com/repos/GrimDawn-max/gd-edit/releases/latest` on
startup and compares the tag against its own version, so the tag must be
`v<major>.<minor>.<patch>` and must sort correctly. It only reports and links —
nothing downloads or installs itself.

### The notes need a "What's new" section

Give the release body a heading that begins **What's new**, and list what the
release *adds* under it. Everything up to the next heading is read out of the
notes and shown to anyone still on an older build, under the update notice:

```markdown
## What's new

- `write character-sheet` — the character as a web page
- `resists` — the ten resistances, computed

## Fixes

- ... everything else, which is not shown in the app
```

Only additions belong in that section. Fixes go below it, where someone looking
for them will find them; a list of everything is a list nobody reads.

Add the same items to `additions` in `src/gd_edit/whats_new.clj`, tagged with
the version. That list is compiled in, and is what a build prints on its first
run after an upgrade — which is the only half that reaches someone who upgraded
from a forum post rather than from the notice.

## What was removed

The upstream project published a single executable to the author's Dropbox and
had the app download and swap itself in place. Those endpoints
(`tiny.cc/gdedexe` and friends) are still live and still serve his 2021 build,
so they were removed rather than left pointing somewhere harmful.
