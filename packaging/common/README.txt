gd-edit @VERSION@
=================

A save game editor for Grim Dawn.

This build adds support for the Fangs of Asterkarn expansion.


INSTALLING
----------

Unzip this folder anywhere you like -- your Desktop, Documents, an external
drive. There is no installer and nothing is written outside this folder.

To uninstall, delete the folder.


REQUIREMENTS: JAVA
------------------

gd-edit needs Java 17 or newer. It is not included in this download.

If you do not already have it, a free build can be downloaded from:

    https://adoptium.net/

Any vendor's build will do -- Temurin, Zulu, Corretto, Oracle, or the one from
your Linux package manager. Nothing here is tied to a particular vendor.

WHICH VERSION: Temurin 21 is recommended. It is the version gd-edit is tested
against. Note that the newest release is not automatically the best choice --
gd-edit uses some long-standing native libraries, and each new Java release
tightens the rules around those. Java 17 and 21 are both known to work.

On the download page, take the JDK, x64, .msi installer. A JRE is technically
enough to run gd-edit, but Adoptium does not publish one for every version, so
the JDK is the simpler choice.

WINDOWS INSTALLER OPTIONS: the Temurin installer leaves some features switched
off by default. Turn on both of these:

    Set JAVA_HOME variable
    Add to PATH

JAVA_HOME is the important one. gd-edit checks it first, so setting it means
gd-edit finds the right Java even if an older one is already on your PATH --
which is common on Windows, where Oracle's Java 8 shim is often still present
from some other program.

(You do not need the "JavaSoft (Oracle) registry keys" option. gd-edit does not
read the registry.)

You do not need to keep Java updated for gd-edit to keep working.

If Java is missing or too old, the launcher will say so -- and name the version
it found and where -- rather than failing cryptically.


RUNNING
-------

  macOS      Double-click gd-edit.app
             (or run gd-edit.command from a terminal)

  Windows    Double-click gd-edit.bat

  Linux      Run ./gd-edit.sh from a terminal
             (mark it executable first if needed: chmod +x gd-edit.sh)

On macOS the first launch is blocked, because the app is not signed by an
identified developer. Getting past it depends on your macOS version.

  macOS 15 (Sequoia) and newer
      Double-click gd-edit.app and dismiss the warning -- it will only offer
      "Move to Trash" or "Done". Then open System Settings > Privacy &
      Security, scroll down, and click "Open Anyway".

      Control-clicking and choosing Open does NOT work on these versions.
      Apple removed that shortcut.

  macOS 14 (Sonoma) and older
      Control-click (or right-click) gd-edit.app, choose Open, then confirm.

Either way you only need to do it once.

If you would rather use the terminal, this clears the warning outright and
works on every version:

    xattr -dr com.apple.quarantine /path/to/gd-edit.app


FIRST RUN: POINTING IT AT YOUR GAME
-----------------------------------

gd-edit tries to find your Grim Dawn save folder and installation folder
automatically. If it cannot, it will tell you at startup, and you set them
yourself from the prompt:

    savedir <full path to your save folder>
    gamedir <full path to your Grim Dawn installation>

For example:

    savedir C:\Users\you\Documents\My Games\Grim Dawn\save\main
    gamedir C:\Program Files (x86)\Steam\steamapps\common\Grim Dawn

These are remembered in settings.edn next to the app, so you only do it once.
Use "savedir clear" or "gamedir clear" to undo.

The game directory is what lets gd-edit resolve item names and stats. Without
it the editor still runs, but database lookups will not work.

Type "help" at the prompt for the full command list.


BACK UP YOUR SAVES
------------------

This program edits your save files. Back them up before making changes.
gd-edit writes a .bak copy when it saves, but keeping your own copy is wise.


SUPPORT
-------

Support is via the forum page for gd-edit only. Only bug fixes will be reviewed.
Feature requests will not be considered.

    https://forums.crateentertainment.com/t/tool-gd-save-file-editor/35817

The GitHub repository does not accept issues, so the forum thread is the place to
raise one.

It will be updated if a game patch or expansion breaks it. Nothing else is
promised. If something is wrong, back out to a save backup -- that is why the
section above tells you to keep one.


CREDIT
------

gd-edit was written by Odie:

    https://github.com/Odie/gd-edit

That project has not been updated since 2021. This build is a fork of it, kept
running against the current game and added to since. The original work is
Odie's; the faults in this build are not.


LICENSE
-------

gd-edit is distributed under the Eclipse Public License, the same license as the
original. See LICENSE.txt. Third-party components and their licenses are listed
in THIRD-PARTY.txt.
