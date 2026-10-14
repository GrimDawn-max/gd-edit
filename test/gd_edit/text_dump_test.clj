(ns gd-edit.text-dump-test
  "What `write character-text` must keep true.

  The dump exists to be searched, and two things decide whether a search
  answers anything. Both were got wrong in a first attempt and are cheap to get
  wrong again, so they are asserted rather than trusted.

  No escape codes. The strings come from the terminal renderer and carry its
  colours, which the web sheet turns back into styling. Left in, they sit in the
  middle of a phrase -- `+1<esc>[m to all skills` -- and a search for
  \"+1 to all skills\" matches nothing.

  Structure, not a flat list. Finding that a build has a damage conversion is
  the easy half; finding which weapon carries it is the half worth having. Every
  line therefore has an owning heading above it, and the test walks back from a
  known line to prove one is there.

  Like character-stats-test, this needs a Grim Dawn install and these saves, so
  it skips loudly without them."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.commands.text :as text]
            [gd-edit.item-stats :as item-stats]))

(def game-dir
  (or (System/getenv "GD_EDIT_TEST_GAMEDIR")
      "/Volumes/GamesDrive/COGames/GrimDawn/drive_c/GOG Games/Grim Dawn"))

(def save-dir
  (or (System/getenv "GD_EDIT_TEST_SAVEDIR")
      "/Users/pj/Documents/My Games/Grim Dawn/save/main"))

(def available?
  (delay (and (.isDirectory (io/file game-dir))
              (.isDirectory (io/file save-dir))
              (.exists (io/file save-dir "_MaxFluffy" "player.gdc"))
              (item-stats/available?))))

(def ^:private loaded
  (delay
    (require '[gd-edit.io.arz :as arz] '[gd-edit.io.arc :as arc]
             '[gd-edit.app-util :as au])
    (let [load-loc (resolve 'gd-edit.io.arc/load-localization-table)
          load-db  (resolve 'gd-edit.io.arz/load-game-db)
          build-ix (resolve 'gd-edit.app-util/build-db-index)
          loc (reduce merge {} (map #(load-loc (str game-dir % "/resources/Text_EN.arc"))
                                    ["" "/gdx1" "/gdx2" "/gdx3"]))
          db (mapcat #(load-db (str game-dir %) loc)
                     ["/database/database.arz" "/gdx1/database/GDX1.arz"
                      "/gdx2/database/GDX2.arz" "/gdx3/database/GDX3.arz"])
          ix (build-ix db)]
      (intern 'gd-edit.globals 'localization-table (future loc))
      (intern 'gd-edit.globals 'db (future db))
      (intern 'gd-edit.globals 'db-index (future ix))
      (intern 'gd-edit.globals 'db-and-index (future {:db db :index ix}))
      true)))

(defn- dump []
  (require '[gd-edit.io.gdc :as gdc])
  @loaded
  (text/text-for ((resolve 'gd-edit.io.gdc/load-character-file*)
                  (str save-dir "/_MaxFluffy/player.gdc"))))

(defn- owner-of
  "The heading a line sits under, which is what makes a hit useful."
  [lines i]
  (->> (range i -1 -1)
       (map lines)
       (filter #(str/starts-with? % "--- "))
       first))

(defn- skip-note []
  (println (format "  SKIPPED text-dump tests -- needs a Grim Dawn install at %s, saves at %s, and the item stat engine"
                   game-dir save-dir)))

(deftest searchable
  (if-not @available?
    (skip-note)
    (let [out (dump)
          lines (str/split-lines out)]

      (testing "no terminal escape codes survive -- they break a search mid-phrase"
        (is (not (re-find #"" out))))

      (testing "every section is present, so nothing silently stops being dumped"
        (doseq [h ["ATTRIBUTES AND COMBAT" "RESISTANCES" "EQUIPMENT"
                   "SKILLS" "DEVOTIONS" "BUFFS"]]
          (is (some #(= h %) lines) (str "missing section: " h))))

      (testing "item tooltips are here, not just the numbers the CSV already had"
        ;; a conversion appears in no other export gd-edit writes
        (is (some #(str/includes? % "converted to") lines)))

      (testing "a conversion can be traced to the item carrying it"
        (let [i (first (keep-indexed #(when (str/includes? %2 "converted to") %1) lines))]
          (is (some? i))
          (is (some? (owner-of lines i))
              "a matching line with no heading above it answers 'do I have one' and not 'where is it'")))

      (testing "procs carry the chance they fire on, under what they are bound to"
        (let [i (first (keep-indexed #(when (re-find #"Chance (on Attack|when Hit)" %2) %1) lines))]
          (is (some? i) "no proc chance in the dump -- buffs lost their :trigger")
          (is (some? (owner-of lines i))))))))
