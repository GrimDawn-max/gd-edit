(ns gd-edit.resistances
  "What a character's resistances actually are, as the game shows them.

  Resistances are not stored in a save. The game computes them when it loads the
  character, and there is no field to read. This namespace reproduces that
  computation from the save and the game's own database:

      cap    = 80 + every maximum-resistance modifier the character carries
      total  = the items, at their real rolled values
             + their components and augments
             + devotion stars that are passive
             + auras the player has switched on
             - the difficulty penalty for that resistance
      shown  = min(cap, total)

  Every term comes from the game's files rather than from documentation, and the
  result was checked against the character sheet in game rather than against a
  third-party calculator.

  Four things look like contributions and are not. Each was found by computing a
  number the game disagreed with, and each would otherwise have produced a
  confident wrong answer:

    - `Skill_Modifier` records carry resistance values describing what the skill
      does to *enemies*. Word of Pain's `defensiveAether -25` reduces an enemy's
      aether resistance; it is not -25 for the player.

    - `Skill_BuffSelfDuration` devotion stars are temporary buffs the player
      activates. Counting one added a phantom +8 Physical from an ability that
      was not running. Only `Skill_Passive` stars apply permanently.

    - Components and augments are absent from `item-stats/rolled-stats`, which
      returns what the item itself rolled. They were worth 40 Vitality on the
      character this was developed against.

    - Maximum-resistance modifiers are on an item's base record and likewise do
      not appear in `rolled-stats`. Missing them caps Bleeding at 80 when the
      character's real cap is 86.

  Known limitation: on one test character a relic contributed 4 Chaos that the
  game does not apply, and the cause has not been established. The figures here
  are computed rather than read, so `resists` says so on screen -- a discrepancy
  is worth reporting rather than trusting."
  (:require [clojure.string :as str]
            [gd-edit.db-utils :as dbu]
            [gd-edit.item-stats :as item-stats]))

(def resistances
  "The resistances the game shows, in the order it shows them."
  (array-map
   "defensiveFire"      "Fire"
   "defensiveCold"      "Cold"
   "defensiveLightning" "Lightning"
   "defensivePoison"    "Poison & Acid"
   "defensivePierce"    "Pierce"
   "defensiveLife"      "Vitality"
   "defensiveBleeding"  "Bleeding"
   "defensiveAether"    "Aether"
   "defensiveChaos"     "Chaos"
   "defensivePhysical"  "Physical"))

(def ^:private elemental
  "Elemental resistance is an aggregate: it grants each of these in full."
  ["defensiveFire" "defensiveCold" "defensiveLightning"])

(def ^:private fields
  (conj (vec (keys resistances)) "defensiveElementalResistance"))

(def ^:private base-cap 80.0)

(def ^:private penalty-record
  "records/game/balancingadjustment_mp+difficulty_players01.dbr")

(defn- at-level
  "A record value, which may be a flat number or an array indexed by skill level."
  [v level]
  (cond
    (number? v) (double v)
    (sequential? v) (let [i (max 0 (min (dec (count v)) (dec (long (or level 1)))))]
                      (double (nth v i)))
    :else nil))

(defn difficulty
  "Which difficulty the character is on: 0 normal, 1 elite, 2 ultimate.

  The save's :last-difficulty carries flags in its upper bits, so only the low
  two are the difficulty itself."
  [character]
  (bit-and (long (or (:last-difficulty character) 0)) 3))

(defn penalties
  "The per-resistance penalty for a difficulty, from the game's balance record.

  Not a flat -50 in ultimate, which is the usual shorthand. Fire, Cold,
  Lightning, Pierce and Poison take -25 in elite and -50 in ultimate; Aether,
  Chaos, Vitality and Bleeding take nothing in elite and -25 in ultimate; and
  Physical has no entry at all, so it is never penalised."
  [difficulty]
  (let [r (dbu/record-by-name penalty-record)
        i (* 4 (long difficulty))]
    (into {} (for [f (keys resistances)
                   :let [a (get r f)]]
               [f (if (sequential? a) (double (nth a i 0.0)) 0.0)]))))

(defn- add-values
  "Fold one record's resistance values into `acc` at `level`."
  [acc record level]
  (if-not record
    acc
    (reduce (fn [m f]
              (if-let [v (at-level (get record f) level)]
                (update m f (fnil + 0.0) v)
                m))
            acc fields)))

(defn- add-caps
  "Fold one record's maximum-resistance modifiers into `acc`."
  [acc record level]
  (if-not record
    acc
    (reduce (fn [m f]
              (let [specific (at-level (get record (str f "MaxResist")) level)
                    every-one (at-level (get record "defensiveAllMaxResist") level)]
                (cond-> m
                  specific  (update f (fnil + 0.0) specific)
                  every-one (update f (fnil + 0.0) every-one))))
            acc (keys resistances))))

(defn- equipped
  "Every item the character is actually wearing.

  A weapon set that is not in use carries an :unused flag; its weapons are
  carried but contribute nothing."
  [character]
  (->> (concat (:equipment character)
               (->> (:weapon-sets character) (remove :unused) (mapcat :items)))
       (filter #(not-empty (str (:basename %))))))

(defn- passive-devotions
  [character]
  (->> (:skills character)
       (filter #(and (:enabled %)
                     (pos? (long (or (:devotion-level %) 0)))
                     (pos? (long (or (:level %) 0)))))
       (filter #(= "Skill_Passive" (str (get (dbu/record-by-name (:skill-name %)) "Class"))))))

(defn- active-buffs
  "Auras the player has switched on, paired with the buff record they apply."
  [character]
  (for [s (:skills character)
        :when (:skill-active s)
        :let [r (dbu/record-by-name (:skill-name s))
              b (some-> (get r "buffSkillName") not-empty dbu/record-by-name)]
        :when b]
    [b (:level s)]))

(defn contributions
  "Resistance values and cap modifiers, kept separate by where they came from.

  Returns {:sources {source-key {field amount}} :caps {field amount}} so a
  caller can show the breakdown, which is what makes a wrong answer diagnosable."
  [character]
  (let [items (equipped character)
        gear (reduce (fn [m it] (add-values m (item-stats/rolled-stats it) nil)) {} items)
        attached (reduce (fn [m it]
                           (reduce (fn [m p]
                                     (add-values m (some-> (not-empty (str p)) dbu/record-by-name) nil))
                                   m [(:relic-name it) (:augment-name it)]))
                         {} items)
        devo (reduce (fn [m s] (add-values m (dbu/record-by-name (:skill-name s)) (:level s)))
                     {} (passive-devotions character))
        buffs (reduce (fn [m [r lvl]] (add-values m r lvl)) {} (active-buffs character))
        caps (as-> {} $
               ;; cap modifiers sit on base records, not in rolled-stats
               (reduce (fn [m it] (add-caps m (dbu/record-by-name (:basename it)) nil)) $ items)
               (reduce (fn [m it]
                         (reduce (fn [m p]
                                   (add-caps m (some-> (not-empty (str p)) dbu/record-by-name) nil))
                                 m [(:relic-name it) (:augment-name it)]))
                       $ items)
               (reduce (fn [m s] (add-caps m (dbu/record-by-name (:skill-name s)) (:level s)))
                       $ (passive-devotions character))
               (reduce (fn [m [r lvl]] (add-caps m r lvl)) $ (active-buffs character)))]
    {:sources {:gear gear :attachments attached :devotions devo :auras buffs}
     :caps caps}))

(defn compute
  "Every resistance for `character`, with the parts that produced it."
  [character]
  (let [{:keys [sources caps]} (contributions character)
        totals (apply merge-with + {} (vals sources))
        ;; elemental resistance grants fire, cold and lightning alike
        elem (get totals "defensiveElementalResistance" 0.0)
        totals (reduce #(update %1 %2 (fnil + 0.0) elem) totals elemental)
        pen (penalties (difficulty character))]
    (for [[field label] resistances
          :let [total (double (get totals field 0.0))
                cap (+ base-cap (double (get caps field 0.0)))
                p (double (get pen field 0.0))]]
      {:field field
       :label label
       :total total
       :penalty p
       :cap cap
       :shown (long (Math/round (min cap (+ total p))))
       :parts (into {} (for [[k m] sources
                             :let [v (+ (double (get m field 0.0))
                                        (if (some #{field} elemental)
                                          (double (get m "defensiveElementalResistance" 0.0))
                                          0.0))]
                             :when (not (zero? v))]
                         [k v]))})))
