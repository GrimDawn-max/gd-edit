(ns gd-edit.seed-search-test
  "Automated checks for the seed search.

  Split into two halves. The pure tests need nothing but the source and always
  run. The rest need a Grim Dawn installation and the stat engine on the
  classpath; they are skipped, loudly, when either is missing, because a test
  that silently does nothing is worse than one that is absent.

  The valuable checks here are differential: the fitted plan is compared against
  the reference engine over seeds it was never fitted to, and the six items in
  seed-verification-dataset.md are compared against values read off the game's
  own tooltips. Together they answer the only question that matters -- would a
  seed this search returns actually produce that item in game."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.seed-search :as ss]
            [gd-edit.item-stats :as item-stats]))

;; ---------------------------------------------------------------- pure checks

(deftest minstd-stream
  (testing "the generator matches the engine's, including the constructor's step"
    ;; MINSTD from a known state: 16807 * 1 = 16807, then 16807^2.
    (is (= 16807 (ss/step 1)))
    (is (= 282475249 (ss/step 16807)))
    ;; first-draw is two steps in, which is what tripped the first kernel.
    (is (= (ss/step (ss/step 1)) (ss/first-draw 1))))

  (testing "the stream stays inside 32 bits over a long run"
    (is (every? #(and (pos? %) (< % 2147483647))
                (take 5000 (iterate ss/step 682475736))))))

(deftest jitter-rules
  (testing "spread truncates like the engine, and never reaches zero"
    (is (= 12 (ss/spread-of 60.0 20.0)))
    (is (= 1  (ss/spread-of 5.0 20.0)))
    (is (= 1  (ss/spread-of 1.0 20.0)) "a spread of zero would freeze a stat"))

  (testing "a value rolls within base +/- spread"
    (let [base 60.0 spread 12 modulus 25]
      (is (every? #(<= 48.0 (ss/jittered % base spread modulus) 72.0)
                  (take 2000 (iterate ss/step 12345))))))

  (testing "scaling is single precision then truncated"
    ;; the boots: 64 jittered, 40% scale -> 89, as the game shows
    (is (= 89.0 (ss/scaled-value 64.0 40.0)))
    (is (= 60.0 (ss/scaled-value 60.0 0.0))))

  (testing "a conversion percentage cannot escape 0..100"
    (is (every? #(<= 0.0 (ss/converted % 17.0 20.0) 100.0)
                (take 2000 (iterate ss/step 999))))))

(deftest searching-the-lower-half-is-enough
  (testing "a seed and that seed plus the modulus are the same stream"
    ;; This is why the web finders report matches in pairs, and why we only
    ;; scan 2^31-1 seeds. 0xC22EC876 and 0x422EC877 were one such pair.
    (is (= (ss/first-draw 1110362231)
           (ss/first-draw (+ 1110362231 ss/seed-space))))))

;; ------------------------------------------------------- game-data dependent

(def game-dir
  (or (System/getenv "GD_EDIT_TEST_GAMEDIR")
      "/Volumes/GamesDrive/COGames/GrimDawn/drive_c/GOG Games/Grim Dawn"))

(def db-available?
  (delay
    (and (.isDirectory (io/file game-dir))
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
      {:db db :index ix})))

(defn- record-named [n] (get (:index @loaded) n))

(defn- skip-note [what]
  (println (format "  SKIPPED %s -- needs a Grim Dawn install at %s and the stat engine"
                   what game-dir)))

;; Six items created in gd-edit, written to a save, and read off the game's own
;; tooltips. See seed-verification-dataset.md.
(def in-game-observations
  [{:name "Mythical Amatok's Step"
    :record "records/items/gearfeet/d207_feet.dbr" :seed 682475736
    :stats {"offensiveColdModifier" 89.0 "offensiveSlowColdModifier" 81.0
            "characterLifeModifier" 5.0 "characterDefensiveAbility" 70.0
            "characterRunSpeedModifier" 8.0 "defensivePhysical" 4.0
            "defensivePoison" 45.0 "defensiveLife" 21.0
            "defensiveTotalSpeedResistance" 27.0 "defensiveProtection" 1104.0}}
   {:name "Aethercore Bulwark"
    :record "records/items/awakened/gearweapons/shields/c303_shield.dbr" :seed 1497776073
    :stats {"offensiveFireModifier" 135.0 "offensiveAetherModifier" 147.0
            "offensiveSlowFireModifier" 121.0 "defensivePhysical" 9.0
            "defensiveChaos" 35.0}}
   {:name "Amarastan Pauldrons"
    :record "records/items/awakened/gearshoulders/c019_shoulder.dbr" :seed 1557517061
    :stats {"offensivePhysicalModifier" 85.0 "offensivePierceModifier" 63.0
            "offensiveSlowPhysicalModifier" 81.0 "characterOffensiveAbility" 81.0
            "characterDefensiveAbility" 46.0 "characterDeflectProjectile" 7.0
            "defensivePoison" 25.0 "defensiveBleeding" 20.0
            "defensiveProtection" 1326.0}}])

(deftest engine-matches-the-game
  (if-not @db-available?
    (skip-note "engine-matches-the-game")
    (do @loaded
        (doseq [{:keys [name record seed stats]} in-game-observations]
          (testing name
            (let [got (item-stats/rolled-stats
                       {:basename record :seed seed
                        :prefix-name "" :suffix-name "" :modifier-name ""})]
              (is (some? got) (str "no stats computed for " name))
              (doseq [[field want] stats]
                (is (== want (double (get got field -1.0)))
                    (format "%s / %s: game says %s" name field want)))))))))

(def relic-observations
  "Relics read off the game's tooltips, including their completion bonuses.

  The completion bonus model was derived from Mogdrogen's Ardor and then used to
  predict Aurora's three values before they were looked at; all four matched.
  These guard that result."
  [{:name "Mogdrogen's Ardor"
    :record "records/items/gearrelic/d113_relic.dbr" :seed 20971700
    :stats {"characterLife" 540.0}
    :bonus "records/items/lootaffixes/completionrelics/ad10c_leechresist.dbr"
    :bonus-stats {"defensivePetrify" 26.0}}
   {:name "Aurora"
    :record "records/items/gearrelic/d304_relic.dbr" :seed 1463417571
    :stats {"characterLife" 579.0 "defensivePhysical" 5.0
            "defensiveChaos" 28.0 "defensiveBleeding" 21.0}
    :bonus "records/items/lootaffixes/completionrelics/ac07a_phycunspi.dbr"
    :bonus-stats {"characterStrengthModifier" 4.0
                  "characterDexterityModifier" 3.0
                  "characterIntelligenceModifier" 4.0}}])

(deftest relics-and-completion-bonuses-match-the-game
  (if-not @db-available?
    (skip-note "relics-and-completion-bonuses-match-the-game")
    (do @loaded
        (doseq [{:keys [name record seed stats bonus bonus-stats]} relic-observations]
          (testing (str name " -- the relic's own stats")
            (let [got (item-stats/rolled-stats
                       {:basename record :seed seed
                        :prefix-name "" :suffix-name "" :modifier-name ""})]
              (doseq [[field want] stats]
                (is (== want (double (get got field -1.0)))
                    (format "%s / %s: game says %s" name field want)))))
          (testing (str name " -- its completion bonus")
            ;; Rolled from its own stream primed with the item's seed, one draw
            ;; per field, using the bonus record's own jitter.
            (let [cb (item-stats/completion-bonus
                      {:basename record :seed seed :relic-bonus bonus
                       :prefix-name "" :suffix-name "" :modifier-name ""})]
              (is (some? cb) (str "no completion bonus computed for " name))
              (doseq [[field want] bonus-stats]
                (is (== want (double (get (:values cb) field -1.0)))
                    (format "%s completion bonus / %s: game says %s"
                            name field want)))))))))

(deftest pet-bonuses-match-the-game
  (if-not @db-available?
    (skip-note "pet-bonuses-match-the-game")
    (do @loaded
        (testing "Mogdrogen's Ardor -- Bonus to All Pets"
          ;; Rolls from the item's seed on a stream of its own, which the engine
          ;; implements directly. Values read off the game's tooltip.
          (let [rec (record-named "records/items/gearrelic/d113_relic.dbr")
                pb (item-stats/pet-bonus rec {:basename (:recordname rec) :seed 20971700})]
            (is (some? pb) "no pet bonus computed")
            (doseq [[field want] {"offensiveTotalDamageModifier" 103.0
                                  "defensiveAether" 41.0
                                  "defensiveChaos" 45.0}]
              (is (== want (double (get (:values pb) field -1.0)))
                  (format "pet bonus / %s: game says %s" field want)))
            (testing "and its ranges match the brackets the game prints"
              (is (= [80.0 120.0] (get (:ranges pb) "offensiveTotalDamageModifier")))
              (is (= [32.0 48.0] (get (:ranges pb) "defensiveAether")))))))))

(deftest fitted-plan-predicts-the-engine
  (if-not @db-available?
    (skip-note "fitted-plan-predicts-the-engine")
    (do @loaded
        (doseq [{:keys [name record]} in-game-observations]
          (testing name
            (let [plan (ss/fit-plan (record-named record))]
              (is (seq (:entries plan)) (str "nothing fitted for " name))
              (is (ss/plan-valid? plan)
                  (str "plan disagrees with the engine on unseen seeds: " name))))))))

(deftest search-results-hold-up
  (if-not @db-available?
    (skip-note "search-results-hold-up")
    (do @loaded
        (let [rec  (record-named "records/items/gearfeet/d207_feet.dbr")
              plan (ss/fit-plan rec)
              mins {"offensiveColdModifier" 89.0
                    "offensiveSlowColdModifier" 81.0
                    "defensivePoison" 45.0}
              hits (ss/search (ss/compile-search plan mins) 3)]
          (is (= 3 (count hits)) "expected three matches for a loose filter")
          (doseq [seed hits]
            (testing (str "seed " seed)
              ;; The engine, not our kernel, has the final say.
              (let [actual (item-stats/rolled-stats
                            {:basename (:recordname rec) :seed seed
                             :prefix-name "" :suffix-name "" :modifier-name ""})]
                (doseq [[field minimum] mins]
                  (is (>= (double (get actual field -1.0)) minimum)
                      (format "seed %d returned but %s is below the minimum" seed field))))))))))

(deftest impossible-minimums-find-nothing
  (if-not @db-available?
    (skip-note "impossible-minimums-find-nothing")
    (do @loaded
        (let [plan (ss/fit-plan (record-named "records/items/gearfeet/d207_feet.dbr"))
              c    (ss/compile-search plan {"offensiveColdModifier" 9999.0})]
          ;; A bounded slice is enough to make the point without a full sweep.
          (is (empty? (ss/search-range c 1 20000000 3)))))))

(deftest unsearchable-fields-are-excluded-not-guessed
  (if-not @db-available?
    (skip-note "unsearchable-fields-are-excluded-not-guessed")
    (do @loaded
        (let [plan (ss/fit-plan (record-named "records/items/gearfeet/d207_feet.dbr"))]
          (is (every? #(contains? (ss/searchable-fields plan) (:field %))
                      (:entries plan)))
          (is (not-any? (set (:unfittable plan))
                        (keys (ss/searchable-fields plan)))
              "a field we could not explain must never be offered as searchable")))))

(deftest fitting-covers-the-item-database
  (if-not @db-available?
    (skip-note "fitting-covers-the-item-database")
    (let [{:keys [db]} @loaded
          equippable? (fn [r]
                        (and (string? (:recordname r))
                             (str/starts-with? (:recordname r) "records/items/")
                             (#{"Legendary" "Epic" "Rare" "Magical" "Common"}
                              (get r "itemClassification"))
                             (not (str/includes? (:recordname r) "/blueprints/"))
                             (not (str/includes? (:recordname r) "/lootaffixes/"))
                             (get r "itemNameTag")))
          sample (->> db (filter equippable?) (sort-by :recordname) (take-nth 97) (take 60))
          plans  (map (fn [r] [r (ss/fit-plan r)]) sample)
          rolled (reduce + (map (comp count :entries second) plans))
          unfit  (reduce + (map (comp count :unfittable second) plans))]
      (println (format "  coverage: %d items, %d searchable fields, %d unsearchable (%.1f%%)"
                       (count sample) rolled unfit
                       (* 100.0 (/ (double unfit) (max 1 (+ rolled unfit))))))
      (testing "every fitted plan agrees with the engine on unseen seeds"
        (doseq [[r plan] plans]
          (is (ss/plan-valid? plan) (str "plan invalid for " (:recordname r)))))
      (testing "the fitter explains the overwhelming majority of rolled fields"
        (is (< (/ (double unfit) (+ rolled unfit)) 0.10)
            "more than a tenth of fields unexplained suggests a missing model")))))
