(ns gd-edit.character-stats-test
  "Checks the computed character sheet against the real one.

  Every expectation here was read off the character sheet in game rather than
  taken from a wiki or a calculator, including the per-slot armour breakdown and
  the amount by which a capped resistance exceeds its cap. Two of them --
  MaxFluffy's and PoxofFluffy's health -- were written down as predictions
  before being looked at, on characters whose values played no part in deriving
  the formula, so they test it rather than restate it.

  This needs a Grim Dawn install and these particular saves, so it is skipped,
  loudly, when either is missing. It is worth keeping despite that: the errors
  this catches are single points on one stat of one character, which is exactly
  the kind of thing that returns unnoticed. Five separate figures were wrong by
  one until the game's 32-bit float arithmetic was reproduced."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [gd-edit.character-stats :as cs]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.resistances :as res]))

(def game-dir
  (or (System/getenv "GD_EDIT_TEST_GAMEDIR")
      "/Volumes/GamesDrive/COGames/GrimDawn/drive_c/GOG Games/Grim Dawn"))

(def save-dir
  (or (System/getenv "GD_EDIT_TEST_SAVEDIR")
      "/Users/pj/Documents/My Games/Grim Dawn/save/main"))

;; What the character sheet shows. Anything absent was not checked in game and
;; is deliberately not asserted -- a guessed expectation would be worse than none.
(def observed
  {"ColdFluffy"  {:physique 1262 :cunning 380 :spirit 514 :health 31779
                  :energy 2678 :energy-usable 1486
                  :offensive-ability 2126 :defensive-ability 3698 :armour 2324
                  :armour-by-slot {"Head" 2314 "Shoulders" 2314 "Chest" 2575
                                   "Arms" 1855 "Legs" 2575 "Feet" 1855}}
   "HotFluffy"   {:spirit 797 :health 22608 :armour 2199}
   "Mary"        {:physique 1134 :cunning 398 :spirit 652 :health 27422}
   "MaxFluffy"   {:physique 1154 :cunning 365 :spirit 797 :health 22783
                  :energy 3245 :energy-usable 2085
                  :offensive-ability 2071 :defensive-ability 3786 :armour 2199}
   "PoxofFluffy" {:physique 1264 :cunning 300 :spirit 667 :health 22931
                  :energy 3834 :energy-usable 2967
                  :offensive-ability 2538 :defensive-ability 3529 :armour 1955}
   "RedPriest"   {:physique 841 :cunning 841 :spirit 451 :health 8234
                  :energy 2346 :energy-usable 2186
                  :offensive-ability 2010 :defensive-ability 1973 :armour 1086
                  :armour-by-slot {"Head" 1073 "Shoulders" 1291 "Chest" 1042
                                   "Arms" 1130 "Legs" 1165 "Feet" 770}}})

;; Resistances as shown, and how far past the cap a capped one runs -- the game
;; reports the overshoot on hover, which is the only way a capped figure can be
;; checked at all. Without it an error simply hides behind the cap.
(def observed-resistances
  {"ColdFluffy" {"Pierce" [83 182] "Bleeding" [83 45] "Vitality" [80 64]
                 "Aether" [80 35] "Chaos" [80 43] "Poison & Acid" [1 nil]
                 "Physical" [0 nil]}
   "RedPriest"  {"Aether" [64 nil] "Chaos" [67 nil] "Physical" [15 nil]
                 "Bleeding" [86 15]}})

(def available?
  (delay (and (.isDirectory (io/file game-dir))
              (.isDirectory (io/file save-dir))
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

(defn- character [name]
  (require '[gd-edit.io.gdc :as gdc])
  @loaded
  ((resolve 'gd-edit.io.gdc/load-character-file)
   (str save-dir "/_" name "/player.gdc")))

(defn- skip-note [what]
  (println (format "  SKIPPED %s -- needs a Grim Dawn install at %s and saves at %s"
                   what game-dir save-dir)))

(deftest character-sheet-matches-the-game
  (if-not @available?
    (skip-note "character-sheet-matches-the-game")
    (doseq [[who expected] observed]
      (testing who
        (let [got (cs/compute (character who))]
          (doseq [[k v] (dissoc expected :armour-by-slot)]
            (is (= v (get got k))
                (format "%s %s: computed %s, game shows %s" who (name k) (get got k) v)))
          (doseq [[slot v] (:armour-by-slot expected)]
            (is (= v (get-in got [:armour-by-slot slot]))
                (format "%s armour on %s: computed %s, game shows %s"
                        who slot (get-in got [:armour-by-slot slot]) v))))))))

(deftest resistances-match-the-game
  (if-not @available?
    (skip-note "resistances-match-the-game")
    (doseq [[who expected] observed-resistances]
      (testing who
        (let [rows (into {} (for [r (res/compute (character who))] [(:label r) r]))]
          (doseq [[label [value over]] expected
                  :let [row (get rows label)]]
            (is (= value (:shown row))
                (format "%s %s: computed %s, game shows %s" who label (:shown row) value))
            (when over
              (is (= over (long (Math/round (- (+ (:total row) (:penalty row)) (:cap row)))))
                  (format "%s %s over cap: computed %s, game shows %s"
                          who label
                          (long (Math/round (- (+ (:total row) (:penalty row)) (:cap row))))
                          over)))))))))
