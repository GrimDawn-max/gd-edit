(ns gd-edit.item-summary
  (:require [gd-edit.db-utils :as dbu]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.seed-search :as seed-search]
            [clojure.string :as str]
            [gd-edit.equation-eval :as eq]
            [gd-edit.level :as level]
            [com.rpl.specter :as s]
            [gd-edit.utils :as u]
            [gd-edit.globals :as globals]
            [taoensso.timbre :as log]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [jansi-clj.core :refer [red green yellow bold black]]))


(defn sign
  [number]
  (when number
    (if (> number 0)
      "+")))

(defn number
  [number]
  (when number
    (yellow (str number))))

(defn signed-number
  [number]
  (when number
    (yellow
     (str (sign number) number))))

(defn signed-percentage
  [number]
  (when number
    (yellow
     (str (sign number) number "%"))))

(defn percentage
  [number]
  (when number
    (yellow
     (str number "%"))))

(defn record-class-subtype
  [base-record]
  (let [class (base-record "Class")]
    (u/swallow-exceptions
      (str/lower-case (subs class (inc (str/last-index-of class "_")))))))

(defn record-class-display-name
  [class]

  (get {"ArmorJewelry_Amulet" "Amulet"
        "ArmorJewelry_Medal" "Medal"
        "ArmorJewelry_Ring" "Ring"
        "ArmorProtective_Chest" "Chest Armor"
        "ArmorProtective_Feet" "Boots"
        "ArmorProtective_Hands" "Gloves"
        "ArmorProtective_Head" "Helm"
        "ArmorProtective_Legs" "Pants"
        "ArmorProtective_Shoulders" "Shoulder"
        "ArmorProtective_Waist" "Belt"
        "WeaponArmor_Offhand" "Offhand"
        "WeaponArmor_Shield" "Shield"
        "WeaponHunting_Ranged1h" "One-Handed Ranged"
        "WeaponHunting_Ranged2h" "Two-Handed Ranged"
        "WeaponMelee_Axe" "One-Handed Axe"
        "WeaponMelee_Axe2h" "Two-Handed Axe"
        "WeaponMelee_Dagger" "One-Handed Daggar"
        "WeaponMelee_Mace" "One-Handed Mace"
        "WeaponMelee_Mace2h" "Two-Handed Mace"
        "WeaponMelee_Scepter" "One-Handed Scepter"
        "WeaponMelee_Sword" "One-Handed Sword"
        "WeaponMelee_Sword2h" "Two-Handed Sword"
        "OneShot_PotionHealth" "Potion"
        "QuestItem" "Item"
        "ItemArtifact" "Relic"
        }
       class
       "Unknown"))

(defn record-cost
  [record]
  (letfn [(cost [record cost-record]
            (when-let [subtype (record-class-subtype record)]
              (let [;; Only the three attribute requirements are ever displayed. The
                    ;; sibling "cost" equation is priced in gold and unused, and on
                    ;; some records it refers to variables we do not supply --
                    ;; itemcostformulas_epic.dbr's shieldCostEquation wants
                    ;; damageAvgBase and shieldBlockChance -- which evaluated to nil
                    ;; and took the whole item summary down with it.
                    wanted #{"strength" "dexterity" "intelligence"}
                    requirements (->> cost-record
                                      (filter #(and (string? (key %))
                                                    (str/starts-with? (key %) subtype)
                                                    (str/ends-with? (key %) "Equation")))
                                      (map (fn [[k equation]]
                                             [(str/lower-case (subs k
                                                                    (count subtype)
                                                                    (- (count k) (count "Equation"))))
                                              equation]))
                                      (filter #(wanted (first %)))
                                      (map (fn [[name equation]]
                                             ;; An equation we cannot evaluate costs one
                                             ;; requirement line, not the whole summary.
                                             [name (try
                                                     (Math/round
                                                      (eq/evaluate equation
                                                                   {"itemLevel" (record "itemLevel")
                                                                    "totalAttCount" (level/attribute-points-total-at-level (get record "levelRequirement" 1))
                                                                    "itemPrefixCost" 0
                                                                    "itemSuffixCost" 0}))
                                                     (catch Throwable _ nil))]))
                                      (remove #(nil? (second %)))
                                      (into {}))]

                [(when-let [strength-req (requirements "strength")]
                   (format "Required Physique: %s" (number strength-req)))
                 (when-let [dexterity-req (requirements "dexterity")]
                   (format "Required Cunning: %s" (number dexterity-req)))
                 (when-let [spirit-req (requirements "intelligence")]
                   (format "Required Spirit: %s" (number spirit-req)))])))]

    (->> (let [cost-record (or (dbu/record-by-name (record "itemCostName"))
                               (dbu/record-by-name "records/game/itemcostformulas.dbr"))]
           (cost record cost-record))
         (remove nil?))))

(defn augment-skills
  "Given a record, extract the skills it augments"
  [record]
  (->> record
       (filter #(str/starts-with? (key %) "augmentSkill"))
       (group-by (comp last key))
       vals
       (map #(into {} %))
       (s/transform [s/ALL s/MAP-KEYS] (fn [k]
                                         (if (str/includes? k "Name")
                                            :name
                                            :level)))
       (s/transform [s/ALL :name] (fn [record-path]
                                    (dbu/skill-name-from-record (dbu/record-by-name record-path))))
       ;; Some records carry an augmentSkillLevel with no augmentSkillName
       ;; beside it -- the ascended affix ad314b, which is really just
       ;; +3% Physical Resistance, holds two of them. With no skill named
       ;; there is nothing to augment, and formatting it anyway printed
       ;; "+3 to null".
       (remove (comp str/blank? str :name))))

(defn augment-masteries
  "Given a record, extract the skills it augments"
  [record]
  (->> record
       (filter #(str/starts-with? (key %) "augmentMastery"))
       (group-by (comp last key))
       vals
       (map #(into {} %))
       (s/transform [s/ALL s/MAP-KEYS] (fn [k]
                                         (if (str/includes? k "Name")
                                           :name
                                           :level)))
       (s/transform [s/ALL :name] (fn [record-path]
                                    (dbu/skill-name-from-record (dbu/record-by-name record-path))))))

(defn skill-modifiers
  [record]
  (->> record
       (filter #(or (str/starts-with? (key %) "modifiedSkillName")
                    (str/starts-with? (key %) "modifierSkillName")))
       (group-by #(last (key %)))
       vals
       (map #(into {} %))
       (s/transform [s/ALL s/MAP-KEYS] (fn [k]
                                         (if (str/includes? k "modified")
                                           :name
                                           :modifier)))
       (s/transform [s/ALL :name] #(dbu/skill-name-from-record (dbu/record-by-name %)))
       (s/transform [s/ALL :modifier] #(dbu/record-by-name %))))

(defn maybe-choose-by-skill-level
  "A record field's value may be an array. In that case, a specific value needs to be chosen.

  The index is the skill level, which is not always known: a skill modifier
  record reached through an item carries its own arrays but nothing saying which
  rank to read. Falling back to the first entry matches what the game shows in
  that case -- Mortar Trap's modifier on Anderos' Amplifier holds [100 200] and
  the game displays 100% -- and it is in any case better than refusing to print
  the item at all, which is what throwing here used to do."
  [record v]
  (if (vector? v)
    (let [skill-level (record "itemSkillLevel")]
      (if (and (integer? skill-level)
               (< -1 skill-level (count v)))
        (nth v skill-level)
        (first v)))
    v))

(defn maybe-resolve-v
  [record v]
  (maybe-choose-by-skill-level record v))

(defn collect-val-range-
  [record val-name]
  (->> (vals (select-keys record [(str val-name "Min") (str val-name "Max")]))
       (map #(maybe-resolve-v record %))))

(defn collect-val-range
  [record val-name]
  (let [v (->> (collect-val-range- record val-name)
             (map u/maybe-int))]
    (if (and (= (count v) 2)
             (= (first v) (second v)))
      (first v)
      v)))

(defn range-str
  "A range as \"low-high\", or just the number when it has collapsed.

  collect-val-range hands back a bare value when a stat's low and high are the
  same, so anything walking its result has to cope with both shapes. That case
  used to be rare enough to go unnoticed; showing real rolled values makes a
  min and max land on the same number far more often."
  [range]
  (yellow
   (str/join "-" (if (sequential? range) range [range]))))

(def effect-types
  [{:name "Physical", :type :immediate, :record-ref "Physical"}
   {:name "Internal Trauma", :type :dot, :record-ref "SlowPhysical"}
   {:name "Fire", :type :immediate, :record-ref "Fire"}
   {:name "Burn" :type :dot, :record-ref "SlowFire"}
   {:name "Cold", :type :immediate, :record-ref "Cold"}
   {:name "Frostburn" :type :dot, :record-ref "SlowCold"}
   {:name "Lightning", :type :immediate, :record-ref "Lightning"}
   {:name "Electrocute" :type :dot, :record-ref "SlowLightning"}
   {:name "Acid", :type :immediate, :record-ref "Acid"}
   {:name "Poison" :type :dot, :record-ref "Poison"}
   {:name "Vitality", :type :immediate, :record-ref "Life"}
   {:name "Vitality Decay" :type :dot, :record-ref "SlowLife"}
   {:name "Pierce", :type :immediate, :record-ref "Pierce"}
   {:name "Bleeding" :type :dot, :record-ref "Bleeding"}
   {:name "Aether", :type :immediate, :record-ref "Aether"}
   {:name "Chaos", :type :immediate, :record-ref "Chaos"}
   {:name "Life Leech", :type :immediate, :record-ref "SlowLifeLeach"}
   {:name "Mana Leech", :type :immediate, :record-ref "SlowLifeLeach"}])

(def ^:private damage-type-display
  "The name the game prints for each damage type the database names differently.

  The database calls Vitality \"Life\" and Vitality Decay \"SlowLife\", so a
  conversion printed straight from the record reads \"Life Damage converted
  to...\" where the game says \"Vitality Damage converted to...\"."
  (into {} (for [{:keys [name record-ref]} effect-types] [record-ref name])))

(defn damage-type-name
  "A record's damage type as the game names it.

  Conversions can list several types separated by semicolons, so each part is
  translated and anything unrecognised is passed through unchanged."
  [t]
  (when t
    (->> (str/split (str t) #";")
         (map #(get damage-type-display % %))
         (str/join ";"))))

(defn split-camelcase
  [s]
  (->> (clojure.string/split s #"(?=[A-Z])")
       (map clojure.string/lower-case)))

(defn camelcase->keywords
  [s]
  (->> (split-camelcase s)
       (map keyword)))

(defn keywords->camelcase
  [s]
  (let [strs (map #(name %) s)]
    (apply str
           (first strs)
           (map #(str/capitalize %) (rest strs)))))

(def effect-components
  (sort-by #(count (:components %)) >
           [{:name "Poison & Acid", :components #{:defensive :poison}}
            {:name "Physical", :components #{:physical}}
            {:name "Internal Trauma", :components #{:slow :physical}}
            {:name "Fire", :components #{:fire}}
            {:name "Burn" , :components #{:slow :fire}}
            {:name "Cold", , :components #{:cold}}
            {:name "Frostburn" , :components #{:slow :cold}}
            {:name "Lightning", :components #{:lightning}}
            {:name "Electrocute" , :components #{:slow :lightning}}
            {:name "Acid", :components #{:poison}}
            {:name "Poison" :components #{:slow :poison}}
            {:name "Vitality", :components #{:life}}
            {:name "Vitality Decay" :components #{:slow :life}}
            {:name "Pierce", :components #{:pierce}}
            {:name "Bleeding" :components #{:bleeding}}
            {:name "Aether", :components #{:aether}}
            {:name "Chaos", :components #{:chaos}}
            {:name "Life Leech", :components #{:life :leech}}
            {:name "Life Leech", :components #{:life :leach}}
            {:name "Mana Leech", :components #{:mana :leach}}
            {:name "Block", :components #{:block}}
            {:name "Elemental", :components #{:elemental}}
            ]))

(defn effect-by-components
  [components]
  (some #(when (every? (into #{} components) (:components %))
           %)
        effect-components))

(defn effect-name
  [components]
  (:name (effect-by-components components)))

(defn looks-like-effect-keyname
  [keyname]
  (let [components (camelcase->keywords keyname)]
    (when (or (#{"skillCooldownTime"
                 "petBonusName"
                 "weaponDamagePct"
                 "petLimit"
                 "petBurstSpawn"
                 "augmentAllLevel"
                 "waveDistance"
                 "spawnObjectsTimeToLive"
                 "lifeMonitorPercent"
                 "damageAbsorptionPercent"
                 "damageAbsorptionReflectPercent"
                 "piercingProjectile"
                 }
               keyname)
              (contains? #{:character :defensive :offensive :skill :retaliation :projectile :spark :conversion} (first components))
              (effect-by-components components))
      true)))

(defn contains?+
  [coll x]

  (cond
    (set? coll)
    (coll x)

    (map? coll)
    (coll x)

    :else
    (some #(= x %) coll)))

(defn val->string-
  [v & opts]
  (let [signed? (contains?+ opts :signed)
        percentage? (contains?+ opts :percentage)
        negative? (contains?+ opts :negative)]
    (cond->> v
      negative?
      (-)
      (and signed? percentage?)
      (signed-percentage)
      (and (not signed?) percentage?)
      (percentage)
      (and signed? (not percentage?))
      (signed-number)
      :else
      (number))))

(defn val-or-range->string
  [v & opts]
  ;; (u/print-line v)
  (if (seq? v)
    (if (> (count v) 1)
      (apply format "%s-%s"
             (map #(apply val->string- % opts) v))
      (apply val->string- (first v) opts))
    (apply val->string- v opts)))

(defn lookup-and-resolve-
  [record fieldname]
  (maybe-resolve-v record (record fieldname)))

(defn lookup-and-resolve
  [record fieldname]
  (u/maybe-int (lookup-and-resolve- record fieldname)))

(defn effect-name
  [record [k v] components effect]

  (cond
    (= k "defensivePoisonDuration")
    "Poison"
    :else
    (:name effect)))

(defn generic-effect-kv->string-
  [record [k v :as kv]]

  ;; (u/print-line "generic-effect:" k)
  (when-not (or (str/ends-with? k "Max")
                (str/ends-with? k "Chance"))
    (let [components (camelcase->keywords k)]
      ;; Does this actually look like an effect?
      (if-let [effect (effect-by-components components)]

        ;; Sometimes, `v` should really be a range of values
        (let [v (cond (= (last components) :min)
                      (collect-val-range record (u/subs+ k 0 -3))

                      ;; Sometimes, `v` will be an array.
                      ;; The actual value to use will be determined by the item skill level
                      :else
                      (maybe-resolve-v record v))

              [message val-display-type] (cond
                                           ;; Things marked as "offensive" describe damage
                                           (= (first components) :offensive)
                                           ["%s %s Damage"]

                                           (and (= (first components) :defensive)
                                                (= (take-last 2 components) [:max :resist]))
                                           ["%s Maximum %s Resistance" [:signed :percentage]]

                                           (and (= (first components) :defensive)
                                                (= (last components) :duration))
                                           ["%s Reduction in %s Duration"  [:signed :percentage]]

                                           ;; Things marked as "defensive" describe resistance
                                           (= (first components) :defensive)
                                           ["%s %s Resistance" [:percentage]]

                                           (= (first components) :retaliation)
                                           ["%s %s Retaliation"]

                                           :else
                                           (log/debug "Unrecognized effect" (u/collect-as-map k v)))]
          (when message
            (let [key-base (cond-> components
                             (= (last components) :min)
                             drop-last)

                  chance (->> (str (keywords->camelcase key-base) "Chance")
                              (lookup-and-resolve record)
                              percentage)

                  val-display-type (if (contains?+ components :modifier)
                                     [:signed :percentage]
                                     val-display-type)
                  params (list (apply val-or-range->string v val-display-type) (effect-name record kv components effect))
                  [message params] (if chance
                                     [(str "%s Chance of " message) (conj params chance)]
                                     [message params])
                  ]

              ;; (pprint  message)
              ;; (pprint  params)
              (apply format message params))))))))

(defn slow-effect-kv->string-
  [record [k v :as kv]]

  ;; (u/print-line "slow effect:" k)
  (let [components (camelcase->keywords k)
        effect (effect-by-components components)]
    (cond
      ;; Without a damage type there is no sentence to build -- the line would
      ;; read "160 null Damage over 2 seconds", which tells a reader less than
      ;; printing nothing does.
      (nil? (:name effect))
      nil

      (= [:duration :min] (take-last 2 components))
      (let [key-base (keywords->camelcase (drop-last 2 components))
            duration v ;(maybe-resolve-v record v)
            total-dmg (->> key-base
                           (collect-val-range record)
                           (#(if (sequential? %) % [%]))
                           (map #(* % duration)))]

        (when-not (empty? total-dmg)
          (if (contains?+ components :retaliation)
            (if-let [chance (lookup-and-resolve record (str key-base "Chance"))]
              (format "%s Chance of %s %s Retaliation Damage over %s seconds"
                      (percentage chance)
                      (range-str total-dmg)
                      (:name effect)
                      duration)
              (format "%s %s Retaliation Damage over %s seconds"
                      (range-str total-dmg)
                      (:name effect)
                      duration))
            (format "%s %s Damage over %s seconds"
                    (range-str total-dmg)
                    (:name effect)
                    duration))))

      (= [:duration :modifier] (take-last 2 components))
      (format "%s %s Duration" (signed-percentage v) (:name effect))

      (str/ends-with? k "Modifier")
      (generic-effect-kv->string- record kv))))

(defn organize-map
  [m]
  (->> (sort-by key m)
       (into (sorted-map))))

(def effect-string-map
  {"augmentAllLevel" ["%s to all Skills" [:signed]]
   "characterAttackSpeedModifier" ["%s Attack Speed" [:signed :percentage]]
   "characterConstitutionModifier" ["%s Constitution" [:percentage]]
   "characterDefensiveAbility" ["%s Defense Ability" [:signed]]
   "characterDefensiveAbilityModifier" ["%s Defensive Ability" [:signed :percentage]]
   "characterDeflectProjectile" ["%s Chance to Avoid Projectiles" [:percentage]]
   "characterDodgePercent" ["%s Chance to Avoid Melee Attacks" [:percentage]]
   "characterDexterity" ["%s Cunning" [:signed]]
   "characterEnergyAbsorptionPercent" ["%s Energy Absorbed from Enemy Spells" [:signed :percentage]]
   "characterHuntingDexterityReqReduction" ["%s Cunning Requirement for Ranged Weapons" [:percentage :negative]]
   "characterIncreasedExperience" ["%s Experience Gained" [:signed :percentage]]
   "characterIntelligence" ["%s Spirit" [:signed]]
   "characterLife" ["%s Health" [:signed]]
   "characterLifeModifier" ["%s Health" [:signed :percentage]]
   ;; The Energy counterpart was missing, so "+20% Energy" rendered as nothing --
   ;; noticed on set bonuses, but it affects any item carrying the field.
   "characterManaModifier" ["%s Energy" [:signed :percentage]]
   "characterLifeRegen" ["%s Health Regenerated per second" [:signed]]
   "characterLifeRegenModifier" ["Increases Health Regeneration by %s" [:percentage]]
   "characterMana" ["%s Energy"]
   "characterManaRegen" ["%s Energy Regenerated per second" [:signed]]
   "characterManaRegenModifier" ["Increases Energy Regeneration by %s" [:percentage]]
   "characterManaLimitReserve" ["%s Energy Reserved"]
   "characterOffensiveAbility" ["%s Offensive Ability" [:signed]]
   "characterSpellCastSpeedModifier" ["%s Casting Speed" [:signed :percentage]]
   "characterStrength" ["%s Physique" [:signed]]
   "characterOffensiveAbilityModifier" ["%s Offensive Ability" [:signed :percentage]]
   "characterRunSpeedModifier" ["%s Movement Speed" [:signed :percentage]]
   "characterDexterityModifier" ["%s Cunning" [:signed :percentage]]
   "characterIntelligenceModifier" ["%s Spirit" [:signed :percentage]]
   "characterStrengthModifier" ["%s Physique" [:signed :percentage]]
   "characterTotalSpeedModifier" ["%s Total Speed" [:signed :percentage]]
   "damageAbsorptionPercent" ["%s Damage Absorption" [:percentage]]
   "damageAbsorptionReflectPercent" ["%s Damage Absorbed Reflected" [:percentage]]
   "defensiveBleedingDuration" ["%s Reduction in Bleeding Duration" [:percentage]]
   "defensiveBlockAmountModifier" ["%s Shield Damage Blocked" [:signed :percentage]]
   "defensiveElementalResistance" ["%s Elemental Resistance" [:percentage]]
   "defensiveFreeze" ["%s Reduced Freeze Duration" [:percentage]]
   "defensivePercentCurrentLife" ["%s Resistance to Life Reduction" [:percentage]]
   "defensivePercentReflectionResistance" ["%s Reflected Damage Reduction" [:percentage]]
   "defensivePetrify" ["%s Reduced Petrify Duration" [:percentage]]
   "defensiveProtection" ["%s Armor"]
   "defensiveProtectionModifier" ["Increases Armor by %s" [:percentage]]
   "defensiveSleep" ["%s Sleep Resistance" [:percentage]]
   "defensiveStun" ["%s Reduced Stun Duration" [:percentage]]
   "defensiveTotalSpeedResistance" ["%s Slow Resistance" [:percentage]]
   "defensiveTrap" ["%s Reduced Entrapment Duration" [:percentage]]
   "lifeMonitorPercent" ["Activates when Health drops below %s" [:percentage]]
   "offensiveCritDamageModifier" ["%s Crit Damage" [:signed :percentage]]
   "offensiveFearMin" ["Terrify target for %s Seconds"]
   "offensiveLifeLeechMin" ["%s of Attack Damage converted to Health" [:percentage]]
   "offensivePierceRatioMin" ["%s Armor Piercing" [:percentage]]
   "offensiveStunMin" ["Stun target for %s Second"]
   "offensiveTotalDamageModifier" ["%s to All Damage" [:signed :percentage]]
   "petBurstSpawn" ["%s Summon" [:signed]]
   "petLimit" ["%s Summon Limit"]
   "piercingProjectile" [(str "%s Chance to passthrough Enemies " (red "(Hidden)")) [:percentage]]
   "projectileExplosionRadius" ["%s Meter Radius"]
   "projectileLaunchNumber" ["%s Projectile(s)"]
   "projectilePiercingChance" ["%s Chance to passthrough Enemies" [:percentage]]
   "retaliationDamagePct" ["%s of Retaliation Damage added to Attack" [:percentage]]
   "retaliationTotalDamageModifier" ["%s to All Retaliation Damage" [:signed :percentage]]
   "skillActiveDuration" ["%s Second Duration"]
   "skillCooldownReduction" ["%s Skill Cooldown Reduction" [:signed :percentage]]
   "skillCooldownTime" ["%s Second Skill Recharge"]
   "skillActiveManaCost" ["%s Active Energy Cost per Second" ]
   "skillChargeDuration" [(str "%s Second Charge Level Duration " (red "(Hidden)"))]
   "skillManaCost" ["%s Energy Cost"]
   "skillManaCostReduction" ["%s Skill Energy Cost" [:signed :percentage :negative]]
   "skillTargetRadius" ["%s Meter Target Area"]
   "spawnObjectsTimeToLive" ["Lives for %s seconds"]
   "waveDistance" ["%s Meter Range"]
   "weaponDamagePct" ["%s Weapon Damage" [:percentage]]
   })

(def effect-ignore-fields
  #{"skillChargeAura"
    "skillChargeMultipliers"
    })

(def ^:dynamic *pet-bonus*
  "The rolled Bonus to All Pets for the item being summarized, or nil.

  Bound alongside *stat-ranges* and used only for the item's own pet block; a
  granted skill's pet bonus is a different record and does not roll from this
  item's seed."
  nil)

(def ^:dynamic *stat-ranges*
  "field -> [low high] for the item being summarized, or nil.

  Bound only while formatting the item's own fields. A granted skill nested in
  the summary is a different record and its stats do not roll from this item's
  seed, so it must not borrow these ranges."
  nil)

(defn effect-kv->string-
  [record [k v :as kv]]

  ;; (u/print-line "effect-kv->string:" k)
  (let [v (lookup-and-resolve record k)
        kv [k v]]
    (when-not (effect-ignore-fields k)
      ;; Many effects only depends on a single own `kv` pair.
      ;; If a `k` can be located in the effect-string-map, we can easily turn this
      ;; `kv` pair into a string
      (if-let [msg (effect-string-map k)]
        (format (first msg) (apply val-or-range->string v (second msg)))

        ;; Some effects require more than 1 `kv` pair to specify
        ;; We can handle this with a bit of custom code
        (cond
          (= k "offensiveElementalResistanceReductionAbsoluteMin")
          (format "%s Reduced target's Elemental Resistances for %s Seconds"
                  v (lookup-and-resolve record "offensiveElementalResistanceReductionAbsoluteDurationMin"))
          (= k "offensiveElementalResistanceReductionAbsoluteDurationMin")
          nil


          (str/starts-with? k "conversionInType")
          ;; Without a percentage there is no conversion to describe.
          (when-let [pct (percentage (lookup-and-resolve record "conversionPercentage"))]
            (let [idx (subs k (count "conversionInType"))]
              (format "%s %s Damage converted to %s Damage"
                      pct
                      (damage-type-name (record (str "conversionInType" idx)))
                      (damage-type-name (record (str "conversionOutType" idx))))))
          (str/starts-with? k "conversionOutType") nil
          (str/starts-with? k "conversionPercentage") nil



          (= k "offensiveFumbleMin")
          (format "%s Chance for target to Fumble attacks for %s Seconds"
                  (percentage v)
                  (lookup-and-resolve record "offensiveFumbleDurationMin"))
          (= k "offensiveFumbleDurationMin") nil

          (= k "offensiveProjectileFumbleMin")
          (format "%s Chance of Impaired Aim to target for %s Seconds"
                  (percentage v)
                  (lookup-and-resolve record "offensiveProjectileFumbleDurationMin"))
          (= k "offensiveProjectileFumbleDurationMin") nil


          (= k "offensiveSlowOffensiveAbilityMin")
          (format "%s Reduced target's Offensive Ability for %s seconds"
                  v
                  (lookup-and-resolve record "offensiveSlowOffensiveAbilityDurationMin"))
          (= k "offensiveSlowOffensiveAbilityDurationMin") nil


          (= k "sparkChance")
          (if (== v 100)
            (format "Affects up to %s targets"
                    (lookup-and-resolve record "sparkMaxNumber"))
            (format "%s Chance of affecting up to %s targets"
                    (percentage v)
                    (lookup-and-resolve record "sparkMaxNumber")))
          (= k "sparkMaxNumber") nil


          (= k "offensivePercentCurrentLifeMin")
          (format "%s Reduction to Enemy's Health"
                  (->> (collect-val-range record "offensivePercentCurrentLife")
                       range-str
                       percentage))
          (= k "offensivePercentCurrentLifeMax") nil

          (= k "retaliationSlowAttackSpeedDurationMin")
          (format "%s Reduced Attack Speed Retaliation for %s seconds"
                  (percentage (lookup-and-resolve record "retaliationSlowAttackSpeedMin"))
                  v)
          (= k "retaliationSlowAttackSpeedMin") nil

          (= k "skillChargeLevel")
          (format "%s Charge Levels: %s"
                  v
                  (->> (record "skillChargeMultipliers")
                       (map #(percentage %))
                       (str/join ", ")))
          (= k "skillChargeMultipliers") nil


          (= k "offensiveTotalDamageReductionPercentDurationMin")
          (format "%s Reduced target's Damage for %s Seconds"
                  (percentage (lookup-and-resolve record "offensiveTotalDamageReductionPercentMin"))
                  v)
          (= k"offensiveTotalDamageReductionPercentMin") nil

          (= k "offensiveSlowTotalSpeedDurationMin")
          (format "%s Slow target for %s seconds"
                  (percentage (lookup-and-resolve record "offensiveSlowTotalSpeedMin"))
                  v)
          (= k "offensiveSlowTotalSpeedMin") nil

          (= k "offensiveFreezeMin")
          ;; The chance is optional. A skill that always freezes simply has no
          ;; offensiveFreezeChance, and formatting that nil printed the word
          ;; "null" where the game prints nothing at all.
          (if-let [chance (percentage (lookup-and-resolve record "offensiveFreezeChance"))]
            (format "%s Chance to Freeze target for %s Second" chance v)
            (format "Freeze target for %s Second" v))
          (= k "offensiveSlowColdMin") nil


          (= k "offensiveTotalResistanceReductionAbsoluteDurationMin")
          (format "%s Reduced target's Resistances for %s Seconds"
                  (percentage (lookup-and-resolve record "offensiveTotalResistanceReductionAbsoluteMin"))
                  v)
          (= k "offensiveTotalResistanceReductionAbsoluteMin") nil

          (= k "projectileFragmentsLaunchNumberMin")
          (format "%s Fragments"
                  (->> (collect-val-range record "projectileFragmentsLaunchNumber")
                       range-str))
          (= k "projectileFragmentsLaunchNumberMax") nil

          (= k "offensiveSlowRunSpeedDurationMin")
          (format "%s Slower target Movement for %s Seconds"
                  (percentage (lookup-and-resolve record "offensiveSlowRunSpeedMin"))
                  v)

          (= k "offensiveSlowRunSpeedMin") nil

          (= k "offensiveTrapMin")
          (format "%s Chance to Immobilize target for %s Seconds"
                  (percentage (lookup-and-resolve record "offensiveTrapChance"))
                  (range-str (collect-val-range record "offensiveTrap")))

          (= k "offensiveTrapChance") nil

          ;;----------------------------------------------------------
          ;; There are other large number of effects that deals with
          ;; describing various kinds of damage.
          ;; We'll try to handle them generically here
          ;;
          (str/includes? k "Slow")
          (slow-effect-kv->string- record kv)

          :else
          (generic-effect-kv->string- record kv))))))

(defn effect-kv->string
  "The effect line, with the range the value rolled from when we know it.

  Showing 89 alone says nothing about whether 89 was lucky; showing it beside
  [67-100] is the difference between a number and a judgement, and it is what
  the game's own tooltip does."
  [record kv]
  (let [s (effect-kv->string- record kv)
        k (first kv)
        ;; A conversion line is keyed on conversionInType, but the stat that
        ;; actually rolled is the matching conversionPercentage -- so look the
        ;; range up under the name it was recorded against.
        range-key (if (and (string? k) (str/starts-with? k "conversionInType"))
                    (let [idx (subs k (count "conversionInType"))]
                      (if (= "" idx) "conversionPercentage" (str "conversionPercentage" idx)))
                    k)]
    (if-let [r (and (string? s) (get *stat-ranges* range-key))]
      (let [[lo hi] r]
        (if (== (double lo) (double hi))
          s
          (format "%s [%s-%s]" s (u/maybe-int lo) (u/maybe-int hi))))
      s)))

(defn sub-record->string
  [record]

  (cond
    (#{"skillLifeBonus" "skillLifePercent"} (key (first record)))
    (let [fields (select-keys record ["skillLifeBonus" "skillLifePercent"])]
      (if (= 2 (count fields))
        (format "%s + %s Health Restored"
                (percentage (lookup-and-resolve record "skillLifePercent"))
                (number (lookup-and-resolve record "skillLifeBonus")))
        (do
          (when-let [v (lookup-and-resolve record "skillLifePercent")]
            (format "%s Health Restored"
                    (percentage v)))
          (when-let [v (lookup-and-resolve record "skillLifeBonus")]
            (format "%s Health Restored"
                    v)))))))

(declare skill-mods-summary effect-summary)

(defn skill-mods-summary
  [record recursion-blocks]

  (flatten
   (for [{:keys [name modifier] :as entry} (skill-modifiers record)]
     (->> (effect-summary modifier (conj recursion-blocks :skill-mods))
          ;; A modifier we have no phrasing for yields nothing rather than the
          ;; word "null" attached to a skill name.
          (keep (fn [desc]
                  (when-not (str/blank? (str desc))
                    (format "%s to %s" desc name))))))))

(defn effect-display-order
  [key-name]
  (cond
    (str/starts-with? key-name "characterStrength") 100
    (str/starts-with? key-name "characterLife") 200
    (str/starts-with? key-name "characterManaRegen") 300
    (str/starts-with? key-name "character") 400
    (str/starts-with? key-name "offensive") 500
    (str/starts-with? key-name "defensive") 600
    :else Integer/MAX_VALUE))

(defn indent
  [s]
  (str "  " s))

(defn indent-all
  "Indent every line of a summary, however deeply it is nested.

  effect-summary returns a mixed sequence: most elements are strings, but a
  record split into sub-records contributes whole sequences, and those are only
  flattened by whoever prints it. Mapping `indent` over that indents the strings
  and leaves everything inside a nested sequence flush, so an indented block
  came out with its first line or two aligned and the rest hanging at the
  margin. Flattening first indents the lines rather than the elements."
  [xs]
  (map indent (flatten xs)))

(defn wrap-if-single
  [v]
  (cond
    (nil? v) nil
    (not (sequential? v)) [v]
    :else v))

(defn split-subrecords
  [record]
  (-> (->> record
           (group-by #(cond
                        (str/starts-with? (key %) "skillLife") "skillLife"))
           (s/transform [s/MAP-VALS] #(into {} %)))
      (set/rename-keys {nil :main})))

(defn- calc-item-skill-level
  [record]
  (when (and (not (record "itemSkillLevel"))
             (record "itemSkillLevelEq")
             (or
              (record "levelRequirement")
              (record "itemLevel")))
    (dec (int (eq/evaluate (record "itemSkillLevelEq")
                           {"itemLevel" (or (record "itemLevel")
                                            (record "levelRequirement"))})))))

(def ^:private set-level-gates
  "Set-record fields whose value gates a companion field.

  A set grants skill points, a mastery bonus, a granted skill and a skill modifier
  at particular piece counts. Each is a name field with no array of its own, paired
  with a level array that says when it applies -- so the array decides, and nothing
  has to be guessed.

  The skill and mastery grants are numbered and there can be several: Blazeseer
  gives three separate +3s at its three-piece tier. Pairing them by index matters
  -- gating name1 on level1 alone would render the other two as \"+3 to null\"."
  (merge
   {"itemSkillLevel"            ["itemSkillName"]
    "itemSkillModifierControl"  ["modifierSkillName1" "modifiedSkillName1"]}
   (into {} (for [i (range 1 9)]
              [(str "augmentSkillLevel" i) [(str "augmentSkillName" i)]]))
   (into {} (for [i (range 1 9)]
              [(str "augmentMasteryLevel" i) [(str "augmentMasteryName" i)]]))))

(defn- set-bonus-at
  "The set's bonuses when exactly `n` pieces are worn.

  Every numeric field on a set record is an array with one entry per set member,
  so entry (n-1) is the value at n pieces. Values are cumulative rather than
  additive -- +2000 Health listed at 3, 4 and 5 means +2000 once you reach three,
  not +6000 at five."
  [set-record n]
  (let [at (fn [v] (when (and (sequential? v) (<= n (count v)))
                     (nth v (dec n))))
        numeric (into {} (for [[k v] set-record
                               :when (string? k)
                               :let [x (at v)]
                               :when (and (number? x) (not (zero? x)))]
                           [k x]))
        ;; carry the name fields whose gate is active at this count
        names (into {} (for [[gate companions] set-level-gates
                             :when (contains? numeric gate)
                             c companions
                             :let [v (get set-record c)]
                             :when (string? v)]
                         [c v]))]
    (merge numeric names)))

(defn- set-bonus-tiers
  "Bonuses grouped by the piece count at which each first appears.

  Returns [[n fields] ...]. Listing every count in full would repeat the same
  lines over and over, since the arrays carry a value forward once it applies;
  showing only what is new at each tier is both shorter and how the game presents
  it."
  [set-record members]
  (let [n-members (max 1 (count members))]
    (->> (range 1 (inc n-members))
         (reduce (fn [{:keys [seen out]} n]
                   (let [now (set-bonus-at set-record n)
                         fresh (into {} (remove (fn [[k v]] (= v (get seen k))) now))]
                     {:seen now
                      :out (if (seq fresh) (conj out [n fresh]) out)}))
                 {:seen {} :out []})
         :out)))

(defn effect-summary
  ([record]
   (effect-summary record #{}))

  ([record recursion-blocks]
   ;; (u/print-line "Summarizing: " (:recordname record))

   ;; recursion-blocks is empty only at the top level, which makes it the test
   ;; for "these fields belong to the item itself".
   ;;
   ;; Realized inside the binding, because the result is a lazy sequence and a
   ;; dynamic binding does not survive into whoever consumes it. Without the
   ;; doall a component's stats get printed against the item's ranges: the
   ;; binding has already unwound and the item's are visible again.
   (binding [*stat-ranges* (when (empty? recursion-blocks) *stat-ranges*)]
    (doall

   ;; Some records require a calculated `itemSkillLevel` to full resolve some of its values
   ;; In these cases, the `v` in the record will be a vector.
   ;; `itemSkillLevel` can be used as the index to retrieve the actual value of `v`
   (let [item-skill-level (calc-item-skill-level record)
         record (cond-> record
                    item-skill-level
                    (assoc "itemSkillLevel" item-skill-level))
         sub-records (split-subrecords record)
         record (sub-records :main)
         sub-records (-> sub-records
                         (dissoc :main)
                         vals)]

     (->> (concat

           ;; Racial bonuses
           (flatten
            (for [race-tag (wrap-if-single (record "racialBonusRace"))]
              [(when-let [absolute-dmg (lookup-and-resolve record "racialBonusAbsoluteDamage")]
                 (format "%s Damage to %s" (signed-number absolute-dmg ) (dbu/race-name race-tag)))
               (when-let [percent-def (lookup-and-resolve record "racialBonusPercentDefense")]
                 (format "%s Less Damage from %s" (percentage percent-def ) (dbu/race-name race-tag)))
               ;; through lookup-and-resolve like its two neighbours: on a skill
               ;; record this is a value per rank -- Oathkeeper's weapon attack
               ;; carries 20 of them -- and reading it raw hands a vector to
               ;; code that expects a number, which throws rather than printing
               (when-let [dmg-percent (lookup-and-resolve record "racialBonusPercentDamage")]
                 (format "%s Damage to %s" (signed-percentage dmg-percent) (dbu/race-name race-tag)))]))

           ;; General effects
           (for [kv (->> record
                         (filter #(and (string? (key %))
                                       (looks-like-effect-keyname (key %))))
                         (sort-by key #(< (effect-display-order %) (effect-display-order %2))))]
             (effect-kv->string record kv))

           (for [sub-record sub-records]
             (sub-record->string (cond-> sub-record
                                   (record "itemSkillLevel")
                                   (assoc "itemSkillLevel" (record "itemSkillLevel")))))

           ;; Additional skills. A granted skill without a level has no "+N" to
           ;; show, and printing "null to Recklessness" is worse than naming the
           ;; skill on its own.
           (for [skill (augment-skills record)]
             (if-let [lvl (signed-number (:level skill))]
               (format "%s to %s" lvl (:name skill))
               (:name skill)))

           ;; Skill modifications
           (when-not (recursion-blocks :skill-mods)
             (skill-mods-summary record recursion-blocks))


           ;; Pet skills
           (when-not (recursion-blocks :pet-skill)
             (when-let [pet-skill (dbu/record-by-name (record "petSkillName"))]
               (effect-summary pet-skill (conj recursion-blocks :pet-skill))))

           ;; Mastery augments
           (for [mastery (augment-masteries record)]
             (format "%s to all skills in %s" (signed-number (:level mastery)) (:name mastery)))

           ;; Additioanl sections
           ;; These have their own titles and are indented for readability
           (when-not (recursion-blocks :pets)
             (when-let [pet-bonus-record (dbu/record-by-name (record "petBonusName"))]
               (concat
                ["" "Bonus to All Pets"]
                ;; A pet bonus rolls from the item's seed, so at the top level
                ;; the rolled values and their ranges are used. Deeper down the
                ;; record belongs to a granted skill rather than the item, and
                ;; *pet-bonus* is nil there, so it prints its own values.
                (let [pb (when (empty? recursion-blocks) *pet-bonus*)]
                  (indent-all
                       (binding [*stat-ranges* (:ranges pb)]
                         (doall
                          (effect-summary (merge pet-bonus-record (:values pb))
                                          (if pb #{} (conj recursion-blocks :pets)))))))))) 

           (when-not (recursion-blocks :item-skill)
             (when-let [item-skill (dbu/record-by-name (record "itemSkillName"))]
               (let [item-skill (cond-> item-skill
                                  (record "itemSkillLevel")
                                  (assoc "itemSkillLevel" (record "itemSkillLevel")))]
                 (concat
                  ["" "Granted Skills"
                   (indent (dbu/skill-name-from-record item-skill))]

                  ;; Include skill description
                  (map indent
                       (u/wrap-line 78 (dbu/skill-description-from-record item-skill)))

                  ;; Add the actual effect summary
                  [""]
                  (indent-all
                       (effect-summary item-skill (conj recursion-blocks :item-skill)))))))

           (when-not (recursion-blocks :buff-skill)
             (when-let [buff-skill (dbu/record-by-name (record "buffSkillName"))]
               (let [buff-skill (cond-> buff-skill
                                  (record "itemSkillLevel")
                                  (assoc "itemSkillLevel" (record "itemSkillLevel")))]

                 (effect-summary buff-skill (conj recursion-blocks :buff-skill))))))

          (remove nil?)))))))

(defn record-primary-attributes
  [record]

  [(when-let [defensive-protection (record "defensiveProtection")]
     (effect-kv->string record ["defensiveProtection" defensive-protection]))
   (when-let [physical-dmg (record "offensivePhysicalMin")]
     (effect-kv->string record ["offensivePhysicalMin" physical-dmg]))
   (when-let [pierce-ratio (record "offensivePierceRatioMin")]
     (effect-kv->string record ["offensivePierceRatioMin" pierce-ratio]))
   (->> record
        (filter #(str/starts-with? (key %) "offensiveBase"))
        (map #(effect-kv->string record %)))])

(defn item-summary
  [item]

  (let [;; Show what the item actually rolled rather than its unrolled base
        ;; values. Falls back to the plain record when the stat engine is
        ;; unavailable, so the summary always renders.
        base-record (-> (dbu/record-by-name (:basename item))
                        (item-stats/with-rolled-stats item))
        subtype (record-class-subtype base-record)
        item-skill-level (calc-item-skill-level base-record)]

   ;; Ranges are fitted from the record's *base* values, so the plain record has
   ;; to be used here -- base-record above has the rolled values merged over
   ;; them and would fit nonsense.
   (binding [*stat-ranges* (merge (seed-search/stat-ranges (dbu/record-by-name (:basename item)))
                                  (item-stats/modifier-ranges item))
             *pet-bonus* (item-stats/pet-bonus (dbu/record-by-name (:basename item)) item)]
    ;; Realized inside the binding: the summary is a lazy sequence, and a
    ;; dynamic binding is long gone by the time something else consumes it.
    (doall
     (->>
     [;; Name of item
      (yellow (dbu/item-name item))
      (when-let [item-text (base-record "itemText")]
        (u/wrap-line 80 item-text))

      ;; Item classification. An ascended item shows "Ascended" in place of its
      ;; rarity, the way the game's own tooltip does.
      (let [ascended? (not= "" (str (:ascended-name item)))
            rarities (if ascended?
                       ["Ascended"]
                       (into [] (vals (select-keys base-record ["itemClassification" "armorClassification"]))))
            classifications (conj rarities (record-class-display-name (base-record "Class")))]
        (str/join " " classifications))

      (record-primary-attributes base-record)

      ;; Item effects
      ""
      (effect-summary (->> (dissoc base-record
                                   "defensiveProtection"
                                   "offensivePhysicalMin"
                                   "offensivePierceRatioMin")
                           (filter (fn [[k v]]
                                     (not (str/starts-with? k "offensiveBase"))))
                           (into {})))

      (when-let [prefix (dbu/record-by-name (:prefix-name item))]
        (effect-summary (cond-> prefix
                          item-skill-level
                          (assoc "itemSkillLevel" item-skill-level))))

      (when-let [suffix (dbu/record-by-name (:suffix-name item))]
        (effect-summary (cond-> suffix
                          item-skill-level
                          (assoc "itemSkillLevel" item-skill-level))))

      ;; What has been socketed into or applied to the item. These are separate
      ;; records with their own stats, and the game lists them under the item's
      ;; own, so they are shown here rather than mixed in above. They are
      ;; summarized with a non-empty recursion-blocks so they do not borrow the
      ;; item's rolled ranges -- none of these roll from the item's seed.
      (when-let [component (dbu/record-by-name (:relic-name item))]
        ["" (yellow (or (dbu/item-base-record-get-name component) "Component"))
         (indent-all (effect-summary component #{:component}))])

      (when-let [bonus (dbu/record-by-name (:relic-bonus item))]
        (let [cb (item-stats/completion-bonus item)]
          ["" (yellow "Completion Bonus")
           ;; The bonus rolls from the item's seed, so its values are merged in
           ;; the same way the item's own are, and its ranges are bound so the
           ;; brackets read like the rest of the summary. An empty
           ;; recursion-blocks keeps those ranges for the bonus's own fields;
           ;; anything nested inside it recurses with a non-empty one and loses
           ;; them, which is what we want.
           (indent-all
                (binding [*stat-ranges* (:ranges cb)]
                  (doall (effect-summary (merge bonus (:values cb)) #{}))))]))

      (when-let [augment (dbu/record-by-name (:augment-name item))]
        ["" (yellow (or (dbu/item-base-record-get-name augment) "Augment"))
         (indent-all (effect-summary augment #{:augment}))])

      ;; The Fangs of Asterkarn ascended affix, applied at the Kurnhold altar.
      ;; Every record in the ascended pools has zero jitter, so these do not roll
      ;; and there are no ranges to show -- one fixed affix, chosen at ascension.
      (when-let [ascended (dbu/record-by-name (:ascended-name item))]
        ["" (yellow "Ascended Bonus")
         (indent-all (effect-summary ascended #{:ascended}))])

      ;; Set membership. The bonuses are fixed -- they do not roll and are not
      ;; stored on the item -- but they are the one stat source nothing else here
      ;; shows, so a build cannot be judged without them.
      (when-let [set-record (some-> (base-record "itemSetName") not-empty dbu/record-by-name)]
        (let [members (get set-record "setMembers")
              members (if (sequential? members) members (when members [members]))
              tiers (set-bonus-tiers set-record members)]
          ["" (yellow (str (or (get set-record "setName") "Set")
                           (format " (%d pieces)" (count members))))
           (map (fn [m]
                  (let [nm (or (dbu/item-base-record-get-name (dbu/record-by-name m)) m)]
                    (indent (if (= m (:basename item)) (str nm "  <- this item") nm))))
                members)
           (for [[n fields] tiers]
             [""
              (indent (format "%d pieces:" n))
              (map indent (indent-all (effect-summary fields #{:set})))])]))

      ;; Requirements section
      ""
      (when-let [level-req (base-record "levelRequirement")]
        (format "Required Player Level: %s" (number level-req)))

      (record-cost base-record)

      (format "Item Level: %s" (number (base-record "itemLevel")))]
     flatten
     (remove nil?)
     dedupe)))))

(defn interesting-fields
  [record]
  (dissoc record "physicsFriction" "armorFemaleMesh" "baseTexture" "outlineThickness" "actorRadius" "dropSound3D"
          "scale" "physicsMass" "maxTransparency" "dropSoundWater" "templateName" "mesh" "dropSound" "castsShadows"
          "attributeScalePercent" "bitmap" "armorMaleMesh" "actorHeight" "glowTexture" "bumpTexture"))

(comment
  (do
    (require 'repl
             '[gd-edit.commands.item :as item]
             '[gd-edit.printer :as printer])

    (repl/init)
    (repl/load-character "Odie"))

  (->> (seq @globals/db)
       (reduce (fn [interesting-keynames record]
                 (into interesting-keynames
                       (filter
                        (fn [keyname]
                          (or
                           ;; (str/includes? keyname "offensive")
                           ;; (str/includes? keyname "defensive")
                           ;; (str/includes? keyname "character")
                           ;; (str/includes? keyname "character")
                           ;; (str/includes? keyname "AmountModifier")
                           ;; (str/includes? keyname "Slow")
                           (u/ci-match keyname "reflect")
                           ))
                        (keys record))))
               #{}))

  (->> (seq @globals/db)
       (filter (fn [record]
                 (some
                  (fn [keyname]
                    (u/ci-match keyname "damageAbsorptionReflectPercent"))
                  (keys record))))
       (map :recordname)
       )

  ;; Manual testing rig
  (def test-target (atom {}))

  (let [item-to-test "Titan Pauldrons"
        level-limit 71]
    (when-let [x (item/construct-item item-to-test @globals/db @globals/db-index level-limit)]
      (reset! test-target (dbu/record-by-name (:basename x)))))

  (effect-summary @test-target)


  (defn get-test-item
    [item-name level-limit]
    (when-let [x (item/construct-item item-name @globals/db @globals/db-index level-limit)]
      (reset! test-target x)))

  (get-test-item "Chausses of Barbaros" 84)


  (sort-by (comp name key)
           (-> @test-target
               (dbu/record-field :basename)
               ;; (record-field "modifierSkillName3")
               ;; (record-field "itemSetName")
               ;; (record-field "petSkillName")
               ;; (record-field "petBonusName")
               ;; (record-field "itemSkillName") ;; granted skill
               ;; (record-field "buffSkillName") ;; granted skill redirect
               (dbu/record-field "itemSkillAutoController")
               (interesting-fields)
               ))

  (item-summary @test-target)


  (->> @globals/db
       (map #(% "Class"))
       distinct
       (remove nil?)
       (filter #(str/includes? % "_"))
       (filter #(or (u/ci-match % "weapon")
                    (u/ci-match % "armor")))
       sort
       )

  (defn printall
    [x]
    (doseq [line x]
      (u/print-line line)))

  (-> (dbu/record-by-name
       "records/skills/playerclass08/soultransfer.dbr")
      (assoc "itemSkillLevel" 0)
      effect-summary
      printall
      )

  ;; Test individual items in the inventory
  (doseq [line (item-summary (repl/get-at-path @globals/character "inv/1/items/4"))]
    (u/print-line line))


  ;; Batch test for items that completely fail
  (printer/show-item (repl/get-at-path @globals/character "inv/0/items/80"))
  (for [[idx item] (u/with-idx (repl/get-at-path @globals/character "inv/0/items"))]
    (try
      (if (empty? (item-summary item))
        (str idx " has no summary")
        )
      (catch Exception e (str idx " threw an exception"))))

  )
