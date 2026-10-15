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
            [gd-edit.self-update :as su]
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

(defn- data-fingerprint
  "A short digest of the databases gd-edit reads, or nil.

  This is the identifier that does not depend on anyone's store. Two installs
  with the same fingerprint hold byte-identical game data, which is the only
  question worth asking when a stat, an item or a record disagrees between two
  people -- and unlike a version it notices a mod, a partial update or a file
  that never finished downloading.

  It covers the base database and each expansion's, plus any mod overriding
  them, because that is the set gd-edit actually loads. 182MB digests in about
  half a second, so it is computed rather than cached."
  []
  (u/swallow-exceptions
   (let [files (->> (dirs/get-file-and-overrides dirs/database-file)
                    (filter #(.exists ^java.io.File %)))]
     (when (seq files)
       {:digest (subs (u/md5 (.getBytes (str/join "|" (map u/md5-file files)))) 0 16)
        :count (count files)}))))

(def ^:private gog-product-id "1449651388")

(defn- gog-version-name
  "The name GOG gives `build-id`, such as \"1.3.1.1_b\", or nil.

  The letter that distinguishes a hotfix exists only in the store's packaging,
  so resolving it means asking the store. GOG publishes its build list without
  authentication; Steam has no equivalent, so a Steam install can show its build
  id and nothing more.

  Never throws and never blocks for long: this is a convenience on top of the id,
  which is already enough to tell two installs apart."
  [build-id]
  (when build-id
    (u/swallow-exceptions
     (some (fn [os]
             (let [body (su/fetch-url (str "https://content-system.gog.com/products/"
                                           gog-product-id "/os/" os "/builds?generation=2"))]
               (some (fn [b]
                       (when (= (str (get b "build_id")) (str build-id))
                         (get b "version_name")))
                     (get (json/read-str body) "items"))))
           ["windows" "osx" "linux"]))))

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

    ;; No install path here on purpose. `diag` reports it, and this output is
    ;; made to be pasted into a bug report, where a path is the one line someone
    ;; would have to scrub first.
    (print-pair "reports itself as" (log-version save-dir))
    (let [fp (data-fingerprint)]
      (print-pair "data fingerprint" (when fp (str (:digest fp)
                                                   "  (" (:count fp) " database files)"))))

    (u/newline-)
    (cond
      gog
      (do (print-pair "store" "GOG")
          (print-pair "build id" (:build gog))
          ;; the only place the hotfix letter exists
          (if-let [n (gog-version-name (:build gog))]
            (print-pair "actually" n)
            (do (u/print-indent 1)
                (u/print-line "actually:              "
                              (yellow "unresolved -- GOG unreachable, or the build is not listed")))))

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
    ;; One line. This is a diagnostic someone may run repeatedly, and the
    ;; reasoning -- why a version does not identify a build, why the fingerprint
    ;; is the one to trust -- is in `help gamever`, where it is read once.
    (u/print-line "Quote the fingerprint when reporting anything that depends on game data.")))
