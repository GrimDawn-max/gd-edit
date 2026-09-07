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

  Ascended affixes do not roll: every record under records/items/lootaffixes/ascended/
  has zero lootRandomizerJitter, so there is no seed and no range -- the affix is a
  fixed pick."
  (:require [gd-edit.db-utils :as dbu]
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
