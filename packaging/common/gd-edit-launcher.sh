#!/bin/sh
# gd-edit launcher for macOS and Linux.
#
# Shipped as gd-edit.command on macOS (double-click opens it in Terminal) and
# gd-edit.sh on Linux. Requires a Java runtime; see README.txt.

set -u

MIN_JAVA=17
DOWNLOAD_URL="https://adoptium.net/"

# Resolve the directory this script lives in, following symlinks.
SCRIPT="$0"
while [ -h "$SCRIPT" ]; do
    link=$(readlink "$SCRIPT")
    case "$link" in
        /*) SCRIPT="$link" ;;
        *)  SCRIPT="$(dirname "$SCRIPT")/$link" ;;
    esac
done
DIR="$(cd "$(dirname "$SCRIPT")" && pwd)"

# Locate the jar. It sits beside this script, or inside the .app on macOS.
JAR=""
for candidate in \
    "$DIR/gd-edit-standalone.jar" \
    "$DIR/gd-edit.app/Contents/Java/gd-edit-standalone.jar" \
    "$DIR/../Java/gd-edit-standalone.jar"
do
    if [ -f "$candidate" ]; then JAR="$candidate"; break; fi
done

if [ -z "$JAR" ]; then
    echo "ERROR: could not find gd-edit-standalone.jar next to this script."
    echo "Keep the launcher and the jar together in the same folder."
    exit 1
fi

# ---------------------------------------------------------------- find java
# Checked in order of trustworthiness: an explicit JAVA_HOME, then the OS's own
# registry of installed JDKs, then PATH, then common install locations. The
# last group is deliberately unversioned -- pinning a specific release here is
# what breaks when someone upgrades their JDK.
JAVA_CMD=""

# A candidate only counts if it actually runs. This matters on macOS, where
# /usr/bin/java always exists as a stub even with no JDK installed -- accepting
# it on sight produces Apple's "Unable to locate a Java Runtime" message instead
# of our own, much clearer one.
try_java() {
    [ -n "${1:-}" ] || return 1
    case "$1" in
        */*) [ -x "$1" ] || return 1 ;;
        *)   command -v "$1" >/dev/null 2>&1 || return 1 ;;
    esac
    "$1" -version >/dev/null 2>&1 || return 1
    JAVA_CMD="$1"
    return 0
}

if [ -n "${JAVA_HOME:-}" ]; then
    try_java "$JAVA_HOME/bin/java"
fi

if [ -z "$JAVA_CMD" ] && [ -x /usr/libexec/java_home ]; then
    # macOS: finds Temurin, Zulu, Corretto, Oracle -- anything properly installed
    jh=$(/usr/libexec/java_home -v "$MIN_JAVA+" 2>/dev/null || true)
    [ -n "$jh" ] && try_java "$jh/bin/java"
fi

if [ -z "$JAVA_CMD" ]; then
    try_java java
fi

if [ -z "$JAVA_CMD" ]; then
    for candidate in \
        /opt/homebrew/opt/openjdk/bin/java \
        /opt/homebrew/opt/openjdk@*/bin/java \
        /usr/local/opt/openjdk/bin/java \
        /usr/local/opt/openjdk@*/bin/java \
        /usr/lib/jvm/*/bin/java \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java
    do
        try_java "$candidate" && break
    done
fi

if [ -z "$JAVA_CMD" ]; then
    echo ""
    echo "  gd-edit needs Java $MIN_JAVA or newer, and none could be found."
    echo ""
    echo "  Install a free build of Java from:"
    echo "      $DOWNLOAD_URL"
    echo ""
    echo "  Then run this launcher again."
    echo ""
    exit 1
fi

# ------------------------------------------------------------ version check
# Handles both "1.8.0_452" (old scheme) and "17.0.18" / "21" (current scheme).
raw=$("$JAVA_CMD" -version 2>&1 | head -n 1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')
major=$(echo "$raw" | sed -e 's/^1\.\([0-9]*\).*/\1/' -e 's/^\([0-9]*\).*/\1/')

if [ -n "$major" ] && [ "$major" -lt "$MIN_JAVA" ] 2>/dev/null; then
    echo ""
    echo "  gd-edit needs Java $MIN_JAVA or newer, but found Java $raw at:"
    echo "      $JAVA_CMD"
    echo ""
    echo "  Install a newer build from:"
    echo "      $DOWNLOAD_URL"
    echo ""
    exit 1
fi

# Run from the install folder so settings.edn and gd-edit.log are written beside
# the app rather than into whatever directory the user happened to launch from.
# The .app bundle sets GD_EDIT_WORKDIR, because there the script lives inside
# Contents/MacOS and we want the files outside the bundle.
WORKDIR="${GD_EDIT_WORKDIR:-$DIR}"
if [ -w "$WORKDIR" ]; then
    cd "$WORKDIR"
fi

exec "$JAVA_CMD" \
    -Xms128m \
    -Djna.nosys=true \
    --enable-native-access=ALL-UNNAMED \
    -jar "$JAR" "$@"
