(ns gd-edit.respec-test
  "That respec returns the points a character paid for, and no more.

  It used to refund the sum of every skill level the character held, bought or
  not. A character knows more skills than it bought: the game hands out evade,
  two potion charges and a potion modifier at creation, and a Fangs of Asterkarn
  shapeshift form hands out its two attacks the first time it is used. None cost
  a point, and all of them were being refunded.

  The arithmetic that makes it obvious: a level 100 character has 250 skill
  points at most, and respec returned 254. A brand new level 1 character that
  has spent nothing was handed 4.

  Needs a Grim Dawn install and these saves, so it skips loudly without them."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.commands.respec :as respec]
            [gd-edit.item-stats :as item-stats]))

(def game-dir (System/getenv "GD_EDIT_TEST_GAMEDIR"))
(def save-dir (System/getenv "GD_EDIT_TEST_SAVEDIR"))

(def available?
  (delay (boolean (and game-dir save-dir
                       (.isDirectory (io/file game-dir))
                       (.isDirectory (io/file save-dir))))))

(def ^:private loaded
  (delay
    (require '[gd-edit.io.arz :as arz] '[gd-edit.io.arc :as arc] '[gd-edit.app-util :as au])
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

(defn- refund
  "What respec would leave in the character's pool."
  [name]
  (require '[gd-edit.io.gdc :as gdc])
  @loaded
  (-> ((resolve 'gd-edit.io.gdc/load-character-file)
       (str save-dir "/_" name "/player.gdc"))
      (respec/respec-character-skills nil)
      :skill-points))

(defn- skip-note []
  (println "  SKIPPED respec tests -- set GD_EDIT_TEST_GAMEDIR and GD_EDIT_TEST_SAVEDIR"))

(deftest refunds-only-what-was-paid-for
  (if-not @available?
    (skip-note)
    (do
      (testing "a character that has spent nothing is given nothing"
        ;; the clearest case: level 1, no skills bought, and it was handed 4
        (is (= 0 (refund "EmptyTurtle"))))

      (testing "a finished character gets back the maximum and not a point more"
        ;; 250 is the most a level 100 character can have earned
        (doseq [who ["ColdFluffy" "MaxFluffy" "PoxofFluffy" "TestNight"]]
          (is (= 250 (refund who)) (str who " should refund exactly 250"))))

      (testing "a shapeshifter is not credited for the attacks its form grants"
        ;; MightyFluffy has used the Wereraven form, which hands over Ice Talons
        ;; and Cold Snap at level 1. Counting those made it 256.
        (is (= 250 (refund "MightyFluffy")))))))

(defn- respec-of
  "The character as respec leaves it."
  [name]
  (require '[gd-edit.io.gdc :as gdc])
  @loaded
  (respec/respec-character-skills
   ((resolve 'gd-edit.io.gdc/load-character-file)
    (str save-dir "/_" name "/player.gdc"))
   nil))

(deftest leaves-alone-what-it-did-not-refund
  "Respec used to drop the whole skill list, which threw away far more than it
  refunded. On a finished character that was 72 entries, including evade, both
  potion charges, and 46 potion-modifier entries -- the unlocks among them
  earned through content. None of them cost a point, so none are respec's to
  take."
  (if-not @available?
    (skip-note)
    (doseq [who ["MaxFluffy" "MightyFluffy" "Purslane"]]
      (testing who
        (let [before ((resolve 'gd-edit.io.gdc/load-character-file)
                      (str save-dir "/_" who "/player.gdc"))
              after (respec-of who)
              named (fn [c] (set (map #(str (:skill-name %)) (:skills c))))
              potion-modifiers (fn [c] (count (filter #(str/includes? (str (:skill-name %))
                                                                     "potionmodifiers")
                                                      (:skills c))))]

          (testing "the skills the game grants are still there"
            (doseq [n ["records/skills/default/defaultevade.dbr"
                       "records/skills/default/defaulthealthpotion.dbr"
                       "records/skills/default/defaultmanapotion.dbr"]]
              (is (contains? (named after) n) (str n " should survive a respec"))))

          (testing "and so is every potion-modifier unlock"
            (is (= (potion-modifiers before) (potion-modifiers after))))

          (testing "while everything bought is taken back"
            (is (empty? (filter #'respec/bought? (:skills after))))))))))

