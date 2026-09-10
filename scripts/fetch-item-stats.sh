#!/usr/bin/env bash
# Fetch the item stat engine into java-src/, which the build compiles in.
#
# The engine is marius00's Grim Dawn Item Stats, offered for integration into
# third-party tools that already parse the game database. Its sources are not
# committed here -- java-src/ is gitignored -- so a fresh clone needs this.
#
# Without it gd-edit still builds and runs; item-stats resolves the engine
# reflectively and every entry point returns nil, so the app falls back to
# showing unrolled ranges instead of an item's real values. That is a quiet
# degradation rather than a failure, which is exactly why it is worth being
# deliberate about the version.
#
# See packaging/common/THIRD-PARTY.txt for attribution and terms.

set -euo pipefail

# The commit gd-edit is known to build and pass its tests against. Bump it
# deliberately, and re-run the test suite -- the seed search is fitted to this
# engine's draw order, so a change here can move every rolled item value.
PINNED_SHA="97927f261a489553f79d541ad9f83cf6caa64c81"

# Tried in order. The fork is first so that a disappearance, rename or history
# rewrite upstream cannot stop a build; upstream follows so an unforked
# checkout still works.
REPOS=(
  "GrimDawn-max/GrimDawnItemStats"
  "marius00/GrimDawnItemStats"
)

REF="${GDIS_REF:-$PINNED_SHA}"
SRC_PATH="java-sdk/src/main/java/com/grimdawn/itemstats"
FILES=(Calculator.java ItemStatEngine.java Jitter.java MinstdRandom.java)
DEST="java-src/com/grimdawn/itemstats"

cd "$(dirname "$0")/.."
mkdir -p "$DEST"

for repo in "${REPOS[@]}"; do
  echo "Trying ${repo} at ${REF:0:12}..."
  ok=1
  tmp="$(mktemp -d)"
  for f in "${FILES[@]}"; do
    if ! curl -fsSL "https://raw.githubusercontent.com/${repo}/${REF}/${SRC_PATH}/${f}" -o "${tmp}/${f}"; then
      ok=0; break
    fi
    # a 404 page or an empty file is not a source file
    if [ ! -s "${tmp}/${f}" ] || ! grep -q "package com.grimdawn.itemstats" "${tmp}/${f}"; then
      ok=0; break
    fi
  done
  if [ "$ok" = "1" ]; then
    cp "${tmp}"/*.java "${DEST}/"
    rm -rf "${tmp}"
    echo "Fetched ${#FILES[@]} files from ${repo} into ${DEST}/"
    echo
    echo "Now: clojure -T:build uber   (the build compiles java-src/ when present)"
    exit 0
  fi
  rm -rf "${tmp}"
  echo "  not available there"
done

echo "Could not fetch the engine from any known source." >&2
echo "gd-edit will still build; it will show unrolled item ranges instead of real values." >&2
exit 1
