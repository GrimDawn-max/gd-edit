(ns gd-edit.game-dirs
  (:require [clojure.java.io :as io]
            [gd-edit.utils :as u]
            [gd-edit.globals :as globals]
            [gd-edit.vdf-parser :as vdf]
            [com.rpl.specter :as specter]
            [taoensso.timbre :as t]
            [clojure.string :as str])
  (:import [com.sun.jna.platform.win32 WinReg Advapi32Util]))

(declare looks-like-game-dir)

(defn- parse-int
  "Try to parse the given string as an int. Returns Integer or nil."
  [s]
  (try (Integer/parseInt s)
       (catch Exception _ nil)))

(defn get-steam-path
  "Get steam installation path, or nil if Steam isn't installed.

  Windows records it in the registry; macOS and Linux use fixed locations."
  []

  (cond
    (u/running-windows?)
    (u/log-exceptions-with t/debug
     (Advapi32Util/registryGetStringValue WinReg/HKEY_CURRENT_USER
                                          "SOFTWARE\\Valve\\Steam"
                                          "SteamPath"))

    (u/running-osx?)
    (->> [(u/expand-home "~/Library/Application Support/Steam")]
         (filter u/path-exists?)
         first)

    :else
    (->> [(u/expand-home "~/.steam/steam")
          (u/expand-home "~/.local/share/Steam")
          (u/expand-home "~/.steam/root")]
         (filter u/path-exists?)
         first)))

(defn get-steam-library-folders
  "Retrieves the library folders as defined in steamapps/libraryfolders.vdf

  Steam games are often installed outside the main Steam folder -- a second
  drive, an external disk -- and this is the only record of where."
  []
  (let [steam-path (get-steam-path)
        lib-folder-file (when steam-path
                          (io/file steam-path "steamapps/libraryfolders.vdf"))]
    (when (and lib-folder-file (.exists lib-folder-file))
      (as-> lib-folder-file $
        (vdf/parse $)
        (get $ "libraryfolders")

        (filter #(parse-int (key %)) $)
        (specter/transform [specter/ALL specter/FIRST] parse-int $)

        ;; Sort by the directory priority
        (sort-by first $)
        ;; Grab the directories only
        (map second $)
        (map #(get % "path") $)
        ))))

(defn- subdirs-of
  "Immediate subdirectories of `path`, or nil if it isn't a directory."
  [path]
  (let [dir (io/file path)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory %))))))

(defn- wine-drive-c-dirs
  "Candidate C: drives of Wine prefixes on this machine.

  Grim Dawn has no native macOS or Linux build, so on those platforms both the
  game and its saves live inside a Wine prefix -- a CrossOver or Whisky bottle
  on macOS, a Proton prefix or ~/.wine on Linux."
  []
  (cond
    (u/running-osx?)
    (->> [(u/expand-home "~/Library/Application Support/CrossOver/Bottles")
          (u/expand-home "~/Library/Containers/com.isaacmarovitz.Whisky/Bottles")]
         (mapcat subdirs-of)
         (map #(io/file % "drive_c")))

    (u/running-linux?)
    (concat
     [(io/file (u/expand-home "~/.wine") "drive_c")]
     (->> [(u/expand-home "~/.steam/steam/steamapps/compatdata")
           (u/expand-home "~/.local/share/Steam/steamapps/compatdata")]
          (mapcat subdirs-of)
          (map #(io/file % "pfx" "drive_c"))))

    :else
    []))

(def ^:private wine-game-subpaths
  ["GOG Games/Grim Dawn"
   "Program Files (x86)/Steam/steamapps/common/Grim Dawn"
   "Program Files/Steam/steamapps/common/Grim Dawn"
   "Program Files (x86)/Grim Dawn"])

(defn- steam-library-dirs
  "Every Steam library on this machine: the main install plus any extra library
  folders the user has added."
  []
  (->> (concat [(get-steam-path)] (get-steam-library-folders))
       (remove nil?)
       (distinct)))

(defn- steam-game-dirs
  "Where Steam would have put Grim Dawn.

  Skipped on macOS: Steam runs there, but Grim Dawn ships no macOS build, so a
  native macOS Steam library can never contain it. A Mac user running the game
  through Windows Steam inside a Wine bottle is covered by `wine-game-subpaths`
  instead.

  Under Proton on Linux the game files do live here, outside the prefix -- only
  the saves go into the prefix."
  []
  (when-not (u/running-osx?)
    (map #(.getPath (io/file % "steamapps" "common" "Grim Dawn"))
         (steam-library-dirs))))

(defn- standard-game-dirs
  "Get the game's expected installation paths"
  []

  (concat
   ;; Steam, on every platform
   (steam-game-dirs)

   (if (u/running-windows?)
     ;; GOG's defaults
     [(.getPath (io/file "C:\\" "GOG Games" "Grim Dawn"))
      (.getPath (io/file "C:\\" "Program Files (x86)" "GOG Galaxy" "Games" "Grim Dawn"))]

     ;; Elsewhere the game runs under Wine, so look inside the prefixes too
     (concat
      (when (u/running-linux?)
        [(u/expand-home "~/GOG Games/Grim Dawn")])
      (for [drive-c (wine-drive-c-dirs)
            sub wine-game-subpaths]
        (.getPath (io/file drive-c sub)))))))

(defn- clean-list
  "Removes nil from collection and return a list"
  [coll]

  (->> coll
       (distinct)
       (remove nil?)
       (into [])))

(defn get-game-dir-search-list
  "Returns all possible locations where the game dir might be found.

  Note that this function respects the :game-dir setting in the user's settings.edn file."
  ([]
   (get-game-dir-search-list @globals/settings))

  ([settings]
   (clean-list (into [(get settings :game-dir)] (standard-game-dirs)))))

(defn get-steam-cloud-save-dirs
  "Steam Cloud copies of the saves, one per logged-in Steam account.
  219990 is Grim Dawn's Steam app id."
  []

  ;; Skipped on macOS for the same reason as `steam-game-dirs`: without a macOS
  ;; build there is no macOS Steam install of the game to sync saves for.
  (when-not (u/running-osx?)
    (when-let [steam-path (get-steam-path)]
      (->> (subdirs-of (io/file steam-path "userdata"))
           (map #(io/file % "219990" "remote" "save" "main"))
           (filter #(.exists %))
           (map #(.getPath %))))))

(declare get-game-dir)

(def ^:private save-subpath
  ["Documents" "My Games" "Grim Dawn" "save" "main"])

(defn get-local-save-dir
  "The save folder under the user's own Documents.

  Grim Dawn always writes saves to \"Documents/My Games/Grim Dawn\" relative to
  whichever home directory it sees. On Windows that is the real one; under Wine
  it may instead be the prefix's fake home, which `wine-prefix-save-dirs`
  covers."
  []
  (.getPath (apply io/file (u/home-dir) save-subpath)))

(defn- wine-prefix-save-dirs
  "Save folders inside Wine prefixes, where the game puts them on macOS/Linux
  unless the prefix maps Documents back onto the real home directory."
  []
  (for [drive-c (wine-drive-c-dirs)
        user (or (subdirs-of (io/file drive-c "users")) [])
        :when (not= "Public" (.getName user))]
    (.getPath (apply io/file user save-subpath))))

(defn get-save-dir-search-list
  "Returns all possible locations where the save dir might be found.

  Note that this function respects the :save-dir setting in the user's setting.edn file."
  []

  ;; Each helper yields nothing on platforms where it doesn't apply:
  ;; wine prefixes only exist off Windows, Steam Cloud only where the game can
  ;; actually be installed through Steam.
  (clean-list (concat [(get @globals/settings :save-dir)
                       (get-local-save-dir)]
                      (wine-prefix-save-dirs)
                      (get-steam-cloud-save-dirs))))

(defn save-dir->mod-save-dir
  [save-dir]
  (let [p (u/filepath->components (str save-dir))
        target-idx (.lastIndexOf p "main")
        p (if (not= target-idx -1)
            (assoc p target-idx "user")
            p)]
    (cond-> (->> p
                 u/components->filepath
                 io/file)
      (not= java.io.File (type save-dir))
      (.getAbsolutePath))))

(defn get-all-save-file-dirs
  []
  (->> (map save-dir->mod-save-dir (get-save-dir-search-list))
       (concat (get-save-dir-search-list))
       (map io/file)
       (map #(.listFiles %1))
       (apply concat)
       (filter #(.isDirectory %1))
       (filter #(.exists (io/file %1 "player.gdc")))))


;;------------------------------------------------------------------------------
;; Path construction & File fetching utils
;;------------------------------------------------------------------------------
(def database-file "database/database.arz")
(def templates-file "database/templates.arc")
(def localization-file "resources/Text_EN.arc")
(def texture-file "resources/Items.arc")
(def level-file "resources/Levels.arc")

(defn get-game-dir
  ([]
   (get-game-dir (get-game-dir-search-list)))

  ([game-dirs]
   (->> game-dirs
        (filter looks-like-game-dir)
        (first))))

(defn get-gdx1-dir
  "Returns the directory for Ashes of Malmouth dlc."
  []
  (io/file (get-game-dir) "gdx1"))

(defn get-gdx2-dir
  "Returns the directory for Forgotten Gods dlc."
  []
  (io/file (get-game-dir) "gdx2"))

(defn get-gdx3-dir
  "Returns the directory for Fangs of Asterkarn dlc."
  []
  (io/file (get-game-dir) "gdx3"))

(defn get-mod-dir
  "Returns the configured mod's directory"
  []
  (:moddir @globals/settings))

(defn get-file-override-dirs
  "Returns a list of directories to look for game asset files in"
  []
  [(get-game-dir)
   (get-gdx1-dir)
   (get-gdx2-dir)
   (get-gdx3-dir)
   (get-mod-dir)])

(defn files-with-extension
  [directory ext]
  (->> (io/file directory)
       file-seq
       (filter #(and (.isFile %)
                     (u/case-insensitive= (u/file-extension %) ext)))))

(defn get-mod-db-file
  [mod-dir]

  (when mod-dir
    (let [components (u/path-components (str mod-dir))
          mod-name (last components)]

      (->> (file-seq (io/file mod-dir "database"))
           (filter #(u/case-insensitive= (u/path-basename %) mod-name))
           first))))

(defn get-db-file-overrides
  []
  (->> (concat [(io/file (get-game-dir) database-file)
                (io/file (get-gdx1-dir) "database/GDX1.arz")
                (io/file (get-gdx2-dir) "database/GDX2.arz")
                (io/file (get-gdx3-dir) "database/GDX3.arz")]
               [(get-mod-db-file (get-mod-dir))])
       (filter u/path-exists?)
       (into [])))

(defn resolve-file
  "Resolve `relative-path` against `dir`, falling back to a case-insensitive match
  on each path segment when the exact path does not exist.

  Grim Dawn is not consistent about the case of its asset filenames. gdx3 ships
  `resources/levels.arc`, `resources/items.arc` and `resources/text_en.arc`, where
  the base game, gdx1 and gdx2 all ship `Levels.arc`, `Items.arc` and `Text_EN.arc`.
  That difference is invisible on Windows and macOS, whose filesystems are
  case-insensitive by default, but on Linux it silently drops every Fangs of
  Asterkarn asset -- taking the expansion's shrine names, item names and textures
  with it. `load-shrines-and-gates` swallows the resulting failure, so the only
  symptom is missing data.

  Returns a java.io.File, or nil when nothing matches. The exact path is tried
  first, so the common case costs no extra IO."
  [dir relative-path]

  (when dir
    (let [exact (io/file dir relative-path)]
      (if (u/path-exists? exact)
        exact
        (reduce (fn [^java.io.File parent segment]
                  (or (->> (.listFiles parent)
                           (filter #(u/case-insensitive= (.getName ^java.io.File %) segment))
                           first)
                      (reduced nil)))
                (io/file dir)
                (u/path-components relative-path))))))

(defn get-file-and-overrides
  "Given the relative path of a game asset file, return a vector of all matched files.

  For example, each mod is likely to have a database file and a localization file.
  When we're processing a the database file, then, it's not enough to just process
  the base game's database file, but all active mods also.

  This function builds such a list for callers to process."
  [relative-path]

  (if (= relative-path database-file)
    (get-db-file-overrides)

    (->> (get-file-override-dirs)
         (map #(resolve-file % relative-path))
         (filter some?)
         (into []))))

(defn looks-like-game-dir
  [path]

  (if (and (resolve-file path database-file)
           (resolve-file path localization-file))
    true
    false))

(defn is-cloud-save-dir?
  [dir]
  (->> (get-steam-cloud-save-dirs)
       (map #(.getParent (io/file %)))
       (some #(str/starts-with? dir %))))

(defn is-mod-save-dir?
  [dir]
  (->> (get-save-dir-search-list)
       (map save-dir->mod-save-dir)
       (some #(str/starts-with? dir %))))

(defn is-character-from-cloud-save?
  [character]
  (is-cloud-save-dir? (:meta-character-loaded-from character)))

(defn is-character-from-mod-save?
  [character]
  (is-mod-save-dir? (:meta-character-loaded-from character)))

(defn save-dir-type
  [dir]

  (let [cloud? (is-cloud-save-dir? dir)
        custom? (is-mod-save-dir? dir)
        builder (StringBuilder.)]
    (if cloud?
      (.append builder "cloud")
      (.append builder "local"))
    (when custom?
      (.append builder " custom"))
    (.toString builder)))

;;------------------------------------------------------------------------------
;; Transfer stash
;;------------------------------------------------------------------------------
(defn get-transfer-stash
  [character]

  (when-not (empty? character)
    (let [target-file (if (:hardcore-mode character)
                        "transfer.gsh"
                        "transfer.gst")
          target-dir (cond->>
                         ;; Grab the "top" of the save directory
                         (->> (get-save-dir-search-list)
                              (map #(.getParentFile (io/file %))))

                       ;; If a mod is active, navigate to its directory
                       (and (is-character-from-mod-save? character)
                            (not-empty(get-mod-dir)))
                       (map #(io/file % (u/last-path-component (:moddir @globals/settings))))

                       ;; Grab the first item that has the stash file we're looking for
                       :then
                       (some #(when (or (.exists (io/file % "transfer.gst"))
                                        (.exists (io/file % "transfer.gsh")))
                                %)))]
      (io/file target-dir target-file))))
