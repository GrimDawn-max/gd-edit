(ns gd-edit.commands.gamever
  "What build of Grim Dawn is installed, exactly.

  The version the game reports is not enough to identify a build. Crate's hotfix
  letters live in the distributor's packaging and nowhere in the game: GOG calls
  one build `1.3.1.1_b` while `Engine.dll`, the splash screen and `log.html` all
  say `v1.3.1.1`, the same as the build before it. Two people on different
  databases therefore report the same version, which is exactly the confusion
  worth removing from a bug report.

  The store knows, and records it beside the install:

    GOG     goggame-<id>.info          JSON, `buildId`
    Steam   steamapps/appmanifest_219990.acf   VDF, `AppState.buildid`

  Both are read here. Neither is translated into a release name -- that needs the
  store's API -- but an opaque id is enough for the only question being asked:
  are two people on the same build or not.

  Read-only. Nothing here touches a save."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.utils :as u]
            [clojure.data.json :as json]
            [gd-edit.vdf-parser :as vdf]
            [jansi-clj.core :refer [green red yellow]]))

(def ^:private steam-app-id "219990")

(defn- log-version
  "The version string Grim Dawn writes into its own log, or nil.

  Scraped rather than read from the install, because nothing in the install
  carries it. The log sits beside the save folder, not beside the game."
  [save-dir]
  (when save-dir
    ;; The log sits in the game's documents folder and the save dir is two below
    ;; it -- "Grim Dawn/save/main" -- but which of those a caller hands over
    ;; varies, so walk up a few and take the first that has one.
    (let [candidates (->> (iterate #(some-> ^java.io.File % .getParentFile)
                                  (io/file save-dir))
                          (take-while some?)
                          (take 4)
                          (map #(io/file % "log.html")))]
      (when-let [log (first (filter #(.exists ^java.io.File %) candidates))]
        (with-open [r (io/reader log)]
          (some (fn [l] (second (re-find #"Version:\s*(v[0-9.]+)" l)))
                (take 40 (line-seq r))))))))

(defn- gog-build
  "The GOG build id for the base game, or nil.

  A GOG install drops one `goggame-<id>.info` per product -- the base game and
  every DLC. Only the base game's moves when the game is patched, and it names
  itself by having `gameId` equal to `rootGameId`."
  [game-dir]
  (when game-dir
    (->> (.listFiles (io/file game-dir))
         (filter #(re-matches #"goggame-\d+\.info" (.getName ^java.io.File %)))
         (keep (fn [f]
                 (u/swallow-exceptions
                  (let [m (json/read-str (slurp f))]
                    (when (= (get m "gameId") (get m "rootGameId"))
                      {:build (get m "buildId") :name (get m "name")})))))
         first)))

(defn- steam-build
  "The Steam build id for Grim Dawn, or nil.

  Steam records it in the app manifest in whichever library holds the game, not
  beside the game itself, so the libraries are walked. `libraryfolders.vdf` is
  already parsed to find the install; this reads the manifest next to it."
  []
  (->> (dirs/get-steam-library-folders)
       (map #(io/file % "steamapps" (str "appmanifest_" steam-app-id ".acf")))
       (filter #(.exists ^java.io.File %))
       (keep (fn [f]
               (u/swallow-exceptions
                (let [app (get (vdf/parse f) "AppState")]
                  {:build (get app "buildid")
                   :updated (get app "LastUpdated")
                   :manifest (.getPath ^java.io.File f)}))))
       first))

(defn- print-pair [label value]
  (u/print-indent 1)
  (u/print-line (format "%-22s %s" (str label ":") (if value (yellow (str value)) (red "not found")))))

(defn gamever-handler
  [_]

  (let [game-dir (dirs/get-game-dir)
        save-dir (first (dirs/get-save-dir-search-list))
        gog (gog-build game-dir)
        steam (steam-build)]

    (u/print-line (green "Game build"))
    (u/newline-)

    (print-pair "install" (when game-dir (str game-dir)))
    (print-pair "version" (log-version save-dir))

    (u/newline-)
    (cond
      gog
      (do (print-pair "store" "GOG")
          (print-pair "build id" (:build gog)))

      steam
      (do (print-pair "store" "Steam")
          (print-pair "build id" (:build steam))
          (print-pair "manifest" (:manifest steam)))

      :else
      (do (u/print-indent 1)
          (u/print-line (red "No store metadata found beside the install."))
          (u/print-indent 1)
          (u/print-line "Only GOG and Steam record a build id; a copy installed any other way")
          (u/print-indent 1)
          (u/print-line "has nothing to read.")))

    (u/newline-)
    (u/print-indent 1)
    (u/print-line "The version above is what the game itself reports, and it does not")
    (u/print-indent 1)
    (u/print-line "distinguish a hotfix: 1.3.1.1 and 1.3.1.1b both say v1.3.1.1. The")
    (u/print-indent 1)
    (u/print-line "build id does. Quote it when reporting anything that depends on")
    (u/print-indent 1)
    (u/print-line "game data.")))
