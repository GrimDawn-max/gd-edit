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

If you do not already have it, install a free build of Java from:

    https://adoptium.net/

Pick the LTS version offered on the front page and run the installer. Any
recent Java will do -- Temurin, Zulu, Corretto, or the one from your Linux
package manager. You do not need to keep it updated for gd-edit to keep
working, and updating it will not break gd-edit.

If Java is missing, the launcher will say so rather than failing cryptically.


RUNNING
-------

  macOS      Double-click gd-edit.app
             (or run gd-edit.command from a terminal)

  Windows    Double-click gd-edit.bat

  Linux      Run ./gd-edit.sh from a terminal
             (mark it executable first if needed: chmod +x gd-edit.sh)

On macOS the first launch may be blocked because the app is not signed by an
identified developer. If that happens, right-click gd-edit.app, choose Open,
and confirm. You only need to do this once.


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


LICENSE
-------

gd-edit is distributed under the Eclipse Public License. See LICENSE.txt.
Third-party components and their licenses are listed in THIRD-PARTY.txt.
