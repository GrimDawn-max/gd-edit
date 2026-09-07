(ns gd-edit.labels
  "Readable names for the game's raw stat fields.

  Records name their stats the way the engine stores them -- offensiveFireMin,
  characterDeflectProjectile -- which is unreadable in a list someone is choosing
  from. gd-edit already phrases most of these for item summaries, so that is the
  first place these look; the rest are filled in here.

  Extracted from find-seed so that anything offering a choice between records can
  describe them the same way."
  (:require [clojure.string :as str]
            [gd-edit.db-utils :as dbu]
            [gd-edit.item-summary :as isum]
            [gd-edit.utils :as u]))

(def ^:private resistance-names
  {"defensivePhysical" "Physical Resistance"
   "defensivePierce" "Pierce Resistance"
   "defensiveFire" "Fire Resistance"
   "defensiveCold" "Cold Resistance"
   "defensiveLightning" "Lightning Resistance"
   "defensivePoison" "Poison & Acid Resistance"
   "defensiveAether" "Aether Resistance"
   "defensiveChaos" "Chaos Resistance"
   "defensiveLife" "Vitality Resistance"
   "defensiveBleeding" "Bleeding Resistance"
   "defensiveElementalResistance" "Elemental Resistance"
   "defensiveProtection" "Armor"
   "defensiveTotalSpeedResistance" "Slow Resistance"
   "defensiveStun" "Reduced Stun Duration"
   "defensiveFreeze" "Reduced Freeze Duration"
   "defensiveAllResistance" "All Resistances"
   "defensiveCrowdControl" "Reduced Crowd Control Duration"})

(def ^:private character-names
  "Stats the summary's own templates do not cover."
  {"characterRunSpeedModifier" "Movement Speed"
   "characterAttackSpeedModifier" "Attack Speed"
   "characterSpellCastSpeedModifier" "Casting Speed"
   "characterOffensiveAbility" "Offensive Ability"
   "characterDefensiveAbility" "Defense Ability"
   "characterDeflectProjectile" "Chance to Avoid Projectiles"
   "characterTotalSpeedModifier" "Total Speed"
   "characterLife" "Health"
   "characterMana" "Energy"
   "characterStrength" "Physique"
   "characterDexterity" "Cunning"
   "characterIntelligence" "Spirit"})

(def ^:private damage-name
  "record-ref -> the name the game gives that damage type."
  (into {} (for [{:keys [name record-ref]} isum/effect-types] [record-ref name])))

(defn field-label
  "A readable name for a stat field, or nil when we have nothing better than the
  field itself.

  gd-edit already knows how to phrase many of these, so the templates it uses for
  item summaries are the first place to look. The damage families are generated
  rather than listed there, so they are reconstructed from the same table the
  summary builds them from."
  [field]
  (or (when-let [tmpl (first (get isum/effect-string-map field))]
        (-> tmpl (str/replace "%s" "") str/trim))
      (get resistance-names field)
      (get character-names field)
      (when-let [[_ ref] (re-matches #"offensive(.+)Modifier" field)]
        (when-let [n (damage-name ref)] (str n " Damage")))
      (when-let [[_ ref] (re-matches #"offensive(.+)(?:Min|Max)" field)]
        (when-let [n (damage-name ref)] (str n " Damage")))
      (when-let [[_ ref] (re-matches #"retaliation(.+)(?:Min|Max)" field)]
        (when-let [n (damage-name ref)] (str n " Retaliation")))
      (when (= field "conversionPercentage") "Damage Conversion")))


(defn stat-label
  "A bonus record described by what it gives, since these have no useful name."
  [rec]
  (->> rec
       (filter (fn [[k v]] (and (string? k) (number? v)
                                (not (#{"lootRandomizerJitter" "levelRequirement"
                                        "lootRandomizerCost" "marketAdjustmentPercent"} k)))))
       (map (fn [[k v]] (format "%s %s" (or (field-label k) k) (u/maybe-int v))))
       (str/join ", ")))

(defn skill-label
  "The player-facing name of a skill record."
  [path]
  (when-let [r (dbu/record-by-name path)]
    (let [tag (dbu/skill-display-name r)]
      (or (get (dbu/localization-table) tag)
          tag
          ;; No display name anywhere -- a handful of internal skills have none.
          ;; The bare filename is the last resort, but ".dbr" in a chooser reads
          ;; as a mistake, so drop the extension and the variant digits.
          (-> (last (str/split (str path) #"/"))
              (str/replace #"\.dbr$" "")
              (str/replace #"\d+$" ""))))))
