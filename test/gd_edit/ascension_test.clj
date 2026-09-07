(ns gd-edit.ascension-test
  "Checks for Fangs of Asterkarn item ascension.

  Everything here is derived from the game database, so these tests need a Grim
  Dawn installation and are skipped -- loudly -- without one, matching the
  convention in seed-search-test.

  The important property under test is that `validate` rejects what the altar
  could not produce while accepting everything it could. A validator that is too
  strict is worse than none: it would block legitimate edits on items we simply
  failed to classify."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.ascension :as ascension]))

(def game-dir "/Volumes/GamesDrive/COGames/GrimDawn/drive_c/GOG Games/Grim Dawn")

(def db-available?
  (delay (.exists (io/file game-dir "database/database.arz"))))

(def loaded
  (delay
    (let [load-loc (requiring-resolve 'gd-edit.io.arc/load-localization-table)
          load-db  (requiring-resolve 'gd-edit.io.arz/load-game-db)
          build-ix (requiring-resolve 'gd-edit.app-util/build-db-index)
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
      {:db db :index ix})))

(defn- skip-note [what]
  (println (format "  SKIPPED %s -- needs a Grim Dawn install at %s" what game-dir)))

;; A plain Common one-handed sword, and a Legendary medal, so both the cheap and
;; the expensive blueprint are exercised.
(def ^:private common-sword
  {:basename "records/items/gearweapons/swords1h/b009f_sword.dbr"})
(def ^:private legendary-medal
  {:basename "records/items/gearaccessories/medals/d013_medal.dbr"})

(deftest legal-set-is-derived-from-the-blueprint
  (if-not @db-available?
    (skip-note "legal-set-is-derived-from-the-blueprint")
    (do @loaded
        (testing "a common 1h sword draws from all ten masteries plus a generic pool"
          (let [legal (ascension/legal-affixes common-sword)]
            (is (seq legal) "a common sword must have a legal affix set")
            ;; Every mastery is represented: the blueprint's mastery entry is a
            ;; fixed ten-element array and the engine indexes it by the
            ;; character, so the item itself is not restricted to one mastery.
            (let [classes (set (keep #(second (re-find #"/mastery/(playerclass\d+)/" %)) legal))]
              (is (= 10 (count classes))
                  "all ten mastery pools should be reachable for the item"))
            ;; and some non-mastery affixes too
            (is (some #(not (str/includes? % "/mastery/")) legal)
                "the generic pool should be present as well")))

        (testing "common and legendary draw from different sets"
          (let [c (ascension/legal-affixes common-sword)
                l (ascension/legal-affixes legendary-medal)]
            (when (and (seq c) (seq l))
              (is (not= c l)
                  "common uses the b tables, legendary the a tables")))))))

(deftest validate-accepts-what-the-altar-produces
  (if-not @db-available?
    (skip-note "validate-accepts-what-the-altar-produces")
    (do @loaded
        (testing "every affix in an item's own legal set is accepted"
          (let [legal (ascension/legal-affixes common-sword)
                rejected (remove #(nil? (ascension/validate common-sword %)) legal)]
            (is (empty? rejected)
                (str "these should all be legal: " (pr-str (take 3 rejected))))))

        (testing "clearing the field is always allowed"
          (is (nil? (ascension/validate common-sword "")))
          (is (nil? (ascension/validate common-sword nil))))

        (testing "an affix from a mastery the character lacks is still accepted"
          ;; Items move between characters through the stash, so the affix on an
          ;; item need not match whoever is holding it.
          (let [soldier (first (sort (filter #(str/includes? % "playerclass01")
                                             (ascension/legal-affixes common-sword))))]
            (when soldier
              (is (nil? (ascension/validate common-sword soldier)))))))))

(deftest validate-rejects-what-it-could-not
  (if-not @db-available?
    (skip-note "validate-rejects-what-it-could-not")
    (do @loaded
        (testing "a record that is not an ascended affix at all"
          (is (some? (ascension/validate
                      common-sword
                      "records/items/lootaffixes/prefix/aa004b_cunmod_01.dbr"))))

        (testing "a record that does not exist"
          (is (some? (ascension/validate
                      common-sword
                      "records/items/lootaffixes/ascended/mastery/playerclass01/nope.dbr"))))

        (testing "an affix from the wrong item category"
          ;; Whatever a legendary medal can take that a common sword cannot.
          (let [medal-only (remove (set (ascension/legal-affixes common-sword))
                                   (ascension/legal-affixes legendary-medal))]
            (when-let [a (first (sort medal-only))]
              (is (some? (ascension/validate common-sword a))
                  "an affix outside the item's own tables must be refused"))))

        (testing "the rejection explains itself"
          (let [msg (ascension/validate
                     common-sword
                     "records/items/lootaffixes/prefix/aa004b_cunmod_01.dbr")]
            (is (string? msg))
            (is (str/includes? msg "ascended")))))))

(deftest unknown-items-are-not-blocked
  (if-not @db-available?
    (skip-note "unknown-items-are-not-blocked")
    (do @loaded
        (testing "an item we cannot classify is allowed through rather than refused"
          ;; An unresolvable basename yields no legal set, and the validator must
          ;; fall open rather than closed -- refusing an edit because this
          ;; namespace failed to classify the item would be the worse failure.
          (let [unknown {:basename "records/items/nonsense.dbr"}]
            (is (nil? (ascension/legal-affixes unknown)))
            (is (nil? (ascension/validate
                       unknown
                       "records/items/lootaffixes/ascended/mastery/playerclass01/b301c.dbr"))
                "an unclassifiable item must not have edits refused")))

        (testing "a relic cannot be ascended, so it has no legal set"
          ;; The altar accepts all gear except relics.
          (is (nil? (ascension/legal-affixes
                     {:basename "records/items/gearrelic/d113_relic.dbr"})))))))

;; A real GrimTools build, saved as a fixture. Every item on it carries an
;; ascendedAffix, so it is an independent check on legal-affixes: the categories
;; and tables here were derived from the blueprints alone, and this is the game's
;; own idea of what goes where. It has already caught two mistakes -- belts being
;; treated as armour rather than accessories, and two-handed spears not being
;; classified at all.
(def ^:private gt-build-fixture "test/resources/gt-build-2mgG9B5Z.json")

(deftest agrees-with-a-real-grimtools-build
  (if-not (and @db-available? (.exists (io/file gt-build-fixture)))
    (skip-note "agrees-with-a-real-grimtools-build")
    (do @loaded
        (let [read-json (requiring-resolve 'clojure.data.json/read-str)
              build (read-json (slurp gt-build-fixture) :key-fn keyword)
              equipment (get-in build [:data :equipment])
              wrong (for [[slot item] equipment
                          :let [affix (:ascendedAffix item)]
                          :when affix
                          :let [problem (ascension/validate {:basename (:item item)} affix)]
                          :when problem]
                      [slot affix (first (str/split-lines problem))])]
          (testing "every ascended affix GrimTools assigns is one we call legal"
            (is (empty? wrong) (str "rejected: " (pr-str (vec wrong)))))
          (testing "the fixture actually exercises something"
            (is (<= 10 (count (filter :ascendedAffix (vals equipment))))
                "expected the build to carry ascended affixes on most slots"))))))
