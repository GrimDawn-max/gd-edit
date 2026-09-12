;; What the item summary cannot phrase.
;;
;;     GD_GAME_DIR="/path/to/Grim Dawn" clojure -M:test -i dev/field-coverage.clj
;;
;; For every field on every item record in the game -- and on the records those
;; items reach: components, augments, granted skills, skill modifiers -- ask the
;; summary to phrase that one field on its own. A numeric field that comes back
;; empty is a stat the game shows and gd-edit does not.
;;
;; This found the weapon speed missing from 2,457 records, soulbound from 1,175,
;; and the three fields behind a skill modifier's block, all of which had been
;; invisible until a screenshot happened to show one of them.
;;
;; Two things it cannot do. It is blind to a stat that takes more than one field
;; -- "15 Bleeding Damage over 3 seconds" needs a Min and a Duration together,
;; so its Min looks unhandled and is not -- which is what `paired?` exists to
;; suppress and why the list wants reading rather than acting on. And it says
;; nothing about a field that is phrased wrongly: "Block Resistance" was
;; well-formed nonsense for a year and only a player's eye caught it.

(require '[gd-edit.db-utils :as dbu] '[gd-edit.item-summary :as isum]
         '[gd-edit.io.gdc :as gdc] '[clojure.string :as str])
(def game-dir
  (or (System/getenv "GD_GAME_DIR")
      (throw (ex-info "Set GD_GAME_DIR to your Grim Dawn folder." {}))))

;; Not stats: identity, artwork, sound, and the plumbing that names other records.
(def ignore
  #{"Class" "FileDescription" "templateName" "ActorName" "ItemText" "itemText"
    "itemLevel" "levelRequirement" "itemClassification" "armorClassification"
    "itemSkillName" "buffSkillName" "petSkillName" "skillDisplayName"
    "skillBaseDescription" "skillUpBitmapName" "skillDownBitmapName"
    "bitmap" "relicBitmap" "shardBitmap" "artifactBitmap" "bitmapName"
    "itemCostName" "itemStyle" "useAnimation" "itemSetName" "skillMaxLevel"
    "skillUltimateLevel" "skillConnectionOff" "skillConnectionOn"})

;; Printed by the summary, but not through effect-kv->string, so asking that
;; about them says nothing and means nothing. The item's own properties and the
;; lines under its name.
(def handled-elsewhere
  #{"characterBaseAttackSpeed" "characterBaseAttackSpeedTag" "soulbound"
    "defensiveBlock" "defensiveBlockChance" "blockRecoveryTime" "blockAbsorption"
    "defensiveProtection" "offensivePhysicalMin" "offensivePierceRatioMin"
    "racialBonusPercentDamage" "racialBonusAbsoluteDamage" "racialBonusPercentDefense"})

;; Engine, presentation and economy. None of it was ever a stat.
(def not-a-stat
  #{"physicsFriction" "physicsMass" "outlineThickness" "scale" "maxTransparency"
    "castsShadows" "actorRadius" "actorHeight" "cameraShakeAmplitude"
    "cameraShakeDuration" "cameraShakeFrequency" "cameraShakeDistance"
    "itemCost" "marketAdjustmentPercent" "lootRandomizerCost" "lootRandomizerJitter"
    "craftingMaterial" "attributeScalePercent" "completedRelicLevel"
    "bonusTableWeight" "levelRequirement" "itemLevel"
    "head" "chest" "shoulders" "hands" "legs" "feet" "amulet" "ring" "medal"
    "belt" "weapon" "offhand" "relic"
    ;; which item kinds an affix may appear on, not anything it grants
    "sword" "sword2h" "axe" "axe2h" "mace" "mace2h" "dagger" "scepter" "spear2h"
    "ranged1h" "ranged2h" "shield" "waist" "offHand" "medalVisible"
    "isPetBonusScaling" "instantCast" "physicsRestitution"})

(defn- paired?
  "Printed through a sibling that names what it applies to -- augmentSkillLevel1
  reads as \"+3 to Fault Line\" only because augmentSkillName1 says which skill.
  Asked about on its own it yields nothing, and rightly."
  [record k]
  (some #(and (not= % k) (contains? record %))
        [(str/replace k #"Level(\d*)$" "Name$1")
         (str/replace k #"Percentage(\d*)$" "InType$1")
         (str/replace k #"Chance$" "")
         (str k "Name")
         (str/replace k #"Max(\d*)$" "Min$1")]))

(defn- interesting? [k v]
  (and (string? k)
       (not (ignore k))
       (not (not-a-stat k))
       (not (handled-elsewhere k))
       (not (re-find #"(?i)sound|^fx|Fx$|Texture|Mesh|Shader|animation|Bitmap|Name$|Tag$|Record$|dbr$" k))
       (or (number? v)
           (and (sequential? v) (every? number? v) (seq v)))))

(let [load-loc (requiring-resolve 'gd-edit.io.arc/load-localization-table)
      load-db  (requiring-resolve 'gd-edit.io.arz/load-game-db)
      build-ix (requiring-resolve 'gd-edit.app-util/build-db-index)
      loc (reduce merge {} (map #(load-loc (str game-dir % "/resources/Text_EN.arc")) ["" "/gdx1" "/gdx2" "/gdx3"]))
      db (mapcat #(load-db (str game-dir %) loc) ["/database/database.arz" "/gdx1/database/GDX1.arz" "/gdx2/database/GDX2.arz" "/gdx3/database/GDX3.arz"])
      ix (build-ix db)]
  (swap! (var-get (requiring-resolve 'gd-edit.globals/settings)) assoc :game-dir game-dir)
  (intern 'gd-edit.globals 'localization-table (future loc))
  (intern 'gd-edit.globals 'db (future db))
  (intern 'gd-edit.globals 'db-index (future ix))
  (intern 'gd-edit.globals 'db-and-index (future {:db db :index ix}))

  (let [missing (atom {})
        seen (atom #{})
        ;; a record and everything it reaches that the summary would recurse into
        reachable (fn reachable [r depth]
                    (when (and r (< depth 3) (not (@seen (:recordname r))))
                      (swap! seen conj (:recordname r))
                      (cons r
                            (mapcat #(reachable (dbu/record-by-name %) (inc depth))
                                    (keep (fn [f] (some-> (get r f) not-empty str))
                                          (concat ["buffSkillName" "petSkillName" "itemSkillName"
                                                   "petBonusName"]
                                                  (for [i (range 1 7)] (str "modifierSkillName" i))))))))
        check (fn [r]
                (doseq [[k v] r
                        :when (interesting? k v)
                        :when (not (paired? r k))
                        :let [out (try (str (isum/effect-kv->string r [k v]))
                                       (catch Throwable _ "THREW"))]
                        :when (str/blank? (str/replace out #"\[[0-9;]*m" ""))]
                  (swap! missing update k (fnil inc 0))))]
    ;; every item in the game, not only what someone happens to be wearing
    (let [items (filter #(re-find #"^(Item|Armor|Weapon)" (str (get % "Class"))) (dbu/db))]
      (println (format "reading %d item records..." (count items)))
      (doseq [r items, x (reachable r 0)]
        (check x)))
    (println "fields the summary prints nothing for, most common first:")
    (println)
    (doseq [[k n] (take 30 (sort-by (comp - val) @missing))]
      (println (format "   %-42s on %3d records" k n)))
    (when (empty? @missing) (println "   (none)"))))
(shutdown-agents)
