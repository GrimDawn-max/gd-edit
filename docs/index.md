# gd-edit

A save game editor for Grim Dawn, driven from a command line rather than a
window full of buttons. It reads your save, shows you what is in it, lets you
change things, and writes it back.

This build supports the current game including the Fangs of Asterkarn expansion.

!!! warning "Back up your saves"

    gd-edit writes a `.bak` copy when it saves, but keep your own copy as well.
    Your saves live in `Documents\My Games\Grim Dawn\save` on Windows, and
    `~/Documents/My Games/Grim Dawn/save` on macOS and Linux.

## Download

[Download the latest release :material-download:](https://github.com/GrimDawn-max/gd-edit/releases/latest){ .md-button .md-button--primary }

Each release has three files attached — `-windows.zip`, `-macos.zip` and
`-linux.zip`. Take the one for your system.

## Installing

Unzip the folder anywhere you like: your Desktop, Documents, an external drive.
There is no installer and nothing is written outside that folder. To uninstall,
delete the folder.

=== "Windows"

    Run **gd-edit.exe**.

    Windows may warn that the publisher is unrecognised. That is because the
    build is not code-signed, which costs money for a free tool. Choose
    **More info → Run anyway** if you are willing to.

=== "macOS"

    Run **gd-edit.command**, or **gd-edit.app** if you prefer an icon.

    macOS blocks the first launch, because the build is not signed by an
    identified developer. How you get past it depends on your version — see
    [the first launch is blocked](faq.md#macos-says-the-app-is-damaged-or-will-not-open).

=== "Linux"

    Run **gd-edit.sh**.

    If it is not executable after unzipping:

    ```
    chmod +x gd-edit.sh
    ```

## Java

gd-edit needs **Java 17 or newer**. It is not included in the download, and you
may already have it.

A free build is available from [Adoptium](https://adoptium.net/). Any vendor's
build works — Temurin, Zulu, Corretto, Oracle, or whatever your Linux package
manager provides. Nothing here is tied to a particular vendor.

!!! tip "Which version"

    **Temurin 21** is what gd-edit is tested against.

    The newest release is not automatically the best choice. gd-edit uses some
    long-standing native libraries, and each new Java release tightens the rules
    around those. Java 17 and 21 are both known to work.

    On the download page take the **JDK**, **x64**, **.msi** installer. A JRE is
    technically enough, but Adoptium does not publish one for every version, so
    the JDK is simpler.

??? info "Windows installer options worth turning on"

    The Temurin installer leaves some features switched off by default. Turn on
    both of these:

    - **Set JAVA_HOME variable**
    - **Add to PATH**

    `JAVA_HOME` is the important one. gd-edit checks it first, so setting it
    means gd-edit finds the right Java even if an older one is already on your
    PATH — which is common on Windows, where Oracle's Java 8 shim is often still
    present from some other program.

    You do not need the *JavaSoft (Oracle) registry keys* option. gd-edit does
    not read the registry.

You do not need to keep Java updated for gd-edit to keep working. If Java is
missing or too old, the launcher says so — naming the version it found and where
— rather than failing cryptically.

## First run

gd-edit looks for your game and saves automatically. Steam and GOG installs in
their usual places are found without help.

If it cannot find them, set the paths yourself:

```
gamedir "C:\Program Files (x86)\Steam\steamapps\common\Grim Dawn"
savedir "C:\Users\You\Documents\My Games\Grim Dawn\save"
```

Quotes matter when a path contains spaces.

Then pick a character from the list, and you are in:

```
Please choose a character to load:
1) Mary (local save)
2) RedPriest (local save)

> 1
```

From there, [the commands](commands.md) describe what you can do, and the
[questions](faq.md) page answers the things people usually ask first.

## Support

Support is via the forum page for gd-edit only. Only bug fixes will be reviewed.
Feature requests will not be considered.

The repository does not accept issues, so the forum thread is the place to raise
one.

It will be updated if a game patch or expansion breaks it. Nothing else is
promised. If something goes wrong, restore a save backup — which is why the
warning at the top of this page is there.

## Credit

gd-edit was written by [Odie](https://github.com/Odie/gd-edit). That project has
not been updated since 2021; this build is a fork of it, kept running against the
current game and added to since. The original work is Odie's.

Distributed under the Eclipse Public License, the same licence as the original.

## Other tools

gd-edit is a command line editor. If that is not what you want:

[**GD Defiler**](https://forums.crateentertainment.com/t/tool-gd-defiler/28264)
:   A save editor with a window rather than a prompt — characters, stats, items
    and stash. Windows only. Updated in July 2026 for the 1.3 save format, so it
    reads current saves.

[**GD Stash**](https://forums.crateentertainment.com/t/tool-gd-stash/29036)
:   A Java application for storing items outside your transfer stash, with
    browsing, filtering and a database of items. Also edits characters.

[**GD Item Assistant**](https://forums.crateentertainment.com/t/tool-grim-dawn-item-assistant/30491)
:   Stores items beyond the transfer stash, with search and cloud backup. Useful
    if your problem is running out of stash space rather than wanting to edit.

[**Grim Dawn Item Database**](https://www.grimtools.com/db/)
:   Not an editor. Part of GrimTools, and the easiest way to look up an item's
    exact name before asking gd-edit for it.

[**GrimTools Build Calculator**](https://www.grimtools.com/calc/)
:   Plan a build in a browser, then hand the link to
    [`make-char`](commands.md#make-char) and gd-edit will build the character.
