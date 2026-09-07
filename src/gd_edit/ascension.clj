(ns gd-edit.ascension
  "Fangs of Asterkarn item ascension.

  An ascended item carries one affix record in its :ascended-name field, applied
  at the Ascension Altar in Kurnhold. Which affixes an item can legally receive is
  fully determined by the game's own data, so this namespace answers that question
  from the database rather than from a hardcoded list.

  Each ascension blueprint -- there are exactly four, one per rarity -- names the
  affix tables for every item category:

      oneHandMeleeTablesAffix     one shared table, not mastery-specific
      oneHandMeleeTablesMastery   a 10-element array, one table per mastery

  So the legal set for an item is the union of the tables its rarity's blueprint
  names for its category. Anything outside that set is an affix the altar could
  never have produced.

  Ascended affixes do not roll. Across all 993 affix records there is not a single
  field whose name ends in Max, and the 923 skill-modifier records they point at
  have only refreshDurationMax, which is a trigger timing rather than a stat. A
  stat carrying a Min with no matching Max is fixed at that Min, so an ascended
  bonus is the same every time and the item's seed has no bearing on it.

  (An earlier version of this note credited a zero lootRandomizerJitter. That
  field is not present on these records at all -- right conclusion, wrong reason.)"
  (:require [gd-edit.db-utils :as dbu]
            [gd-edit.labels :as labels]
            [gd-edit.utils :as u]
            [clojure.string :as str]))

(def ^:private blueprint-root
  "records/items/crafting/blueprints/ascension/craft_ascended_")

(def ^:private class->category
  "Maps a base record's Class to the blueprint field prefix for its category.

  Magic and rare *quality* do not appear here: quality comes from affixes and the
  monster-infrequent classification, not from the item's Class."
  {"ArmorProtective_Head"      "armor"
   "ArmorProtective_Chest"     "armor"
   "ArmorProtective_Shoulders" "armor"
   "ArmorProtective_Legs"      "armor"
   "ArmorProtective_Feet"      "armor"
   "ArmorProtective_Hands"     "armor"
   ;; A belt is an accessory here, not armour, despite its class name -- checked
   ;; against a GrimTools build whose belt carries ao305b, which appears only in
   ;; the accessory affix table. Mapping it by the class prefix was wrong.
   "ArmorProtective_Waist"     "accessory"
   "ArmorJewelry_Medal"        "accessory"
   "ArmorJewelry_Amulet"       "accessory"
   "ArmorJewelry_Ring"         "accessory"
   "WeaponArmor_Shield"        "shield"
   "WeaponArmor_Offhand"       "offhand"
   "WeaponMelee_Sword"         "oneHandMelee"
   "WeaponMelee_Axe"           "oneHandMelee"
   "WeaponMelee_Mace"          "oneHandMelee"
   "WeaponMelee_Dagger"        "oneHandMelee"
   "WeaponMelee_Scepter"       "oneHandMelee"
   "WeaponMelee_Sword2h"       "twoHandMelee"
   "WeaponMelee_Axe2h"         "twoHandMelee"
   "WeaponMelee_Mace2h"        "twoHandMelee"
   "WeaponMelee_Spear2h"       "twoHandMelee"
   "WeaponHunting_Ranged1h"    "oneHandRanged"
   "WeaponHunting_Ranged2h"    "twoHandRanged"})

(defn- blueprint-name-for
  "Which of the four ascension blueprints applies to this base record.

  Magic quality has no blueprint of its own -- it borrows common's. That was
  established by ascending magic items and observing the iron spent, the materials
  consumed, and the affix table set drawn from, all three of which matched common."
  [base-record]
  (case (str (get base-record "itemClassification"))
    "Legendary" "legendary"
    "Epic"      "epic"
    "Rare"      "rare"          ; monster infrequents
    "Common"    "common"        ; and magic, which builds on a common base
    nil))

(defn- table-affixes
  "Every affix record a loot table names."
  [table-name]
  (when-let [r (dbu/record-by-name table-name)]
    (->> r
         (filter (fn [[k v]] (and (string? k) (string? v)
                                  (str/starts-with? k "randomizerName"))))
         (map val)
         set)))

(defn legal-affixes
  "The set of ascended affix records this item could legally have received, or nil
  when the item's category or rarity can't be determined."
  [item]
  (when-let [base (dbu/record-by-name (:basename item))]
    (let [category  (class->category (str (get base "Class")))
          blueprint (blueprint-name-for base)]
      (when (and category blueprint)
        (when-let [bp (dbu/record-by-name (str blueprint-root blueprint ".dbr"))]
          (let [tables (concat (let [v (get bp (str category "TablesAffix"))]
                                 (if (string? v) [v] v))
                               (let [v (get bp (str category "TablesMastery"))]
                                 (if (string? v) [v] v)))]
            (reduce into #{} (keep table-affixes tables))))))))

(defn validate
  "Check that `affix-record` is one the altar could have put on `item`.

  Returns nil when the value is acceptable, or a human-readable string explaining
  why it isn't. Clearing the field (empty string) is always allowed -- that just
  un-ascends the item.

  When the legal set can't be determined the value is accepted: refusing an edit
  because this namespace failed to classify the item would be worse than allowing
  an odd one through."
  [item affix-record]
  (cond
    (or (nil? affix-record) (= "" affix-record))
    nil

    (not (str/includes? affix-record "/lootaffixes/ascended/"))
    (format "%s is not an ascended affix record.\nThose live under records/items/lootaffixes/ascended/."
            affix-record)

    (nil? (dbu/record-by-name affix-record))
    (format "No such record: %s" affix-record)

    :else
    (when-let [legal (legal-affixes item)]
      (when-not (contains? legal affix-record)
        (let [base     (dbu/record-by-name (:basename item))
              category (class->category (str (get base "Class")))
              rarity   (blueprint-name-for base)]
          (format (str "%s is not a legal ascended affix for this item.\n"
                       "It is a %s %s item, and the %s ascension blueprint draws its\n"
                       "%s affixes from a different set (%d legal affixes).\n"
                       "The altar could never produce this combination.")
                  affix-record rarity category rarity category (count legal)))))))

;; ---------------------------------------------------------------------------
;; Describing and offering affixes
;;
;; These records have no name. Ordinary prefixes and suffixes carry a
;; lootRandomizerName -- "of the Boar" -- but not one of the 993 ascended affixes
;; does, so a chooser has to say what an affix *grants* instead.

(def ^:private modifier-noise
  "Fields on a skill-modifier record that describe the skill rather than the bonus.

  skillMaxLevel is how far the modified skill can be pushed, skillChanceWeight is
  internal weighting, and the refresh/targeting fields are how the skill behaves
  rather than anything the player gains. None of them belongs in a label."
  #{"skillMaxLevel" "skillChanceWeight" "overwriteBaseSkill"
    "skillTargetAngle" "skillTargetNumber" "skillTargetRadius"
    "projectileLaunchRotation" "projectileSpeedModifier"})

(defn- mechanics-field?
  [k]
  (or (contains? modifier-noise k)
      (str/starts-with? k "refreshDuration")
      (str/starts-with? k "refreshCooldown")))

(defn- refresh-phrase
  "The refresh fields say a skill can reset its cooldown or extend its duration.

  Those are spread over several fields whose raw names mean nothing to a player,
  but dropping them outright leaves some affixes with an empty description -- and
  two affixes on the same skill then look identical again. Used only when the
  modifier grants no stats of its own."
  [r]
  (let [has? (fn [prefix] (some (fn [[k _]] (and (string? k) (str/starts-with? k prefix))) r))]
    (->> [(when (has? "refreshCooldown") "chance to reset its cooldown")
          (when (has? "refreshDuration") "chance to extend its duration")]
         (remove nil?)
         seq
         (#(when % (str/join ", " %))))))

(defn- modifier-effect
  "What a skill modifier actually grants, as a short phrase.

  Several affixes modify the same skill and differ only here -- two of Arcanist's
  affixes both read \"modifier to Maiven's Sphere of Protection\" and are told
  apart solely by this. Without it a chooser offers identical-looking options."
  [modifier-record]
  (when-let [r (dbu/record-by-name modifier-record)]
    (let [stats (not-empty
                 (labels/stat-label
                  (into {} (remove (fn [[k v]]
                                     (and (string? k)
                                          (or (mechanics-field? k)
                                              ;; the equal upper half of a pair, as above
                                              (and (str/ends-with? k "MaxModifier")
                                                   (= v (get r (str/replace k "MaxModifier" "Modifier")))))))
                                   r))))]
      ;; Nearly every modifier carries refresh triggers, so naming them alongside
      ;; real stats pads every line in the list without telling anyone apart.
      ;; They earn their place only when there is nothing else to say.
      (or stats (refresh-phrase r)))))

(defn affix-label
  "Describe an ascended affix by what it grants.

  The mastery affixes give +N to a skill plus a modifier, so a raw field dump
  reads as gibberish for them -- what matters is the skill name."
  [rec]
  (let [sk (get rec "augmentSkillName1")
        lv (get rec "augmentSkillLevel1")
        modified (get rec "modifiedSkillName1")
        effect (some-> (get rec "modifierSkillName1") modifier-effect)]
    (cond
      (and sk lv)
      (let [granted (or (labels/skill-label sk) "?")
            m (some-> modified labels/skill-label)]
        ;; The modified skill is usually the same one being augmented, in which
        ;; case naming it twice just adds noise.
        (format "+%s to %s%s%s" (u/maybe-int lv) granted
                (if (and m (not= m granted)) (format "   (modifies %s)" m) "")
                (if effect (str " -- " effect) "")))

      modified
      (format "modifier to %s%s" (or (labels/skill-label modified) "?")
              (if effect (str " -- " effect) ""))

      :else
      ;; The generic affixes are plain stat bonuses. Two kinds of noise to drop
      ;; before describing them:
      ;;
      ;;   augmentSkillLevel*    carried with no companion skill name, so it
      ;;                         applies to nothing -- vestigial data
      ;;   *MaxModifier          the upper half of a pair whose halves are equal
      ;;                         here, so naming it repeats the stat beside it
      (labels/stat-label
       (into {} (remove (fn [[k v]]
                          (and (string? k)
                               (or (str/starts-with? k "augmentSkillLevel")
                                   (and (str/ends-with? k "MaxModifier")
                                        (= v (get rec (str/replace k "MaxModifier" "Modifier")))))))
                        rec))))))

(defn mastery-key
  "The playerclassNN an affix belongs to, or nil when it is not mastery-specific."
  [recordname]
  (second (re-find #"/mastery/(playerclass\d+)/" (str recordname))))

(defn character-masteries
  "The playerclassNN keys for the masteries a character has taken.

  A mastery shows up in the skill list as a skill whose record is a
  Skill_Mastery, and the record path names the class."
  [character]
  (->> (:skills character)
       (keep :skill-name)
       (filter #(= "Skill_Mastery" (get (dbu/record-by-name %) "Class")))
       (keep #(second (re-find #"/(playerclass\d+)/" (str %))))
       set))

(defn mastery-display-name
  "The player-facing name of a mastery, given its playerclassNN."
  [pc]
  (some-> (dbu/record-by-name (format "records/skills/%s/_classtraining_%s.dbr"
                                      pc (str/replace pc "playerclass" "class")))
          dbu/skill-display-name
          (as-> tag (or (get (dbu/localization-table) tag) tag))))

(defn candidates
  "Ascended affixes for `item`, grouped for display.

  Returns a seq of [group-name [[label record-name] ...]], the generic affixes
  last under \"Any mastery\". When `masteries` is given, only those masteries are
  offered: the altar draws from the tables for the character's own masteries, so
  everything else is unreachable for them in game."
  ([item] (candidates item nil))
  ([item masteries]
   (when-let [legal (legal-affixes item)]
     (let [rows (->> legal
                     (keep (fn [rn]
                             (when-let [r (dbu/record-by-name rn)]
                               {:record rn :mastery (mastery-key rn) :label (affix-label r)})))
                     (filter (fn [{:keys [mastery]}]
                               (or (nil? masteries) (nil? mastery) (contains? masteries mastery)))))]
       (->> rows
            (group-by :mastery)
            (sort-by (fn [[m _]] [(if m 0 1) (str m)]))
            (map (fn [[m rs]]
                   [(if m (or (mastery-display-name m) m) "Any mastery")
                    (->> rs (map (juxt :label :record)) (sort-by first) vec)])))))))
