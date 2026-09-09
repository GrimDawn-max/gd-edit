(ns gd-edit.resistances
  "What a character's resistances actually are, as the game shows them.

  Resistances are not stored in a save. The game computes them when it loads the
  character, and there is no field to read. This namespace reproduces that
  computation from the save and the game's own database:

      cap    = 80 + every maximum-resistance modifier the character carries
      total  = the items, at their real rolled values
             + their components and augments
             + the set bonus for however many pieces of a set are worn
             + skills an item or component grants, when those apply
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

    - An ascended affix (:ascended-name) grants its resistance flat. Nineteen of
      them carry one, worth 20 to 30. Verified by putting one granting 3 Physical
      on a character with none: the game read 3, which also confirms Physical
      takes no difficulty penalty, since 3 less any penalty would have floored to
      nothing.

    - An illusion (:transmute-name) grants nothing. It names an ordinary item
      whose appearance is borrowed, and 405 of those carry resistances -- reading
      them would silently add a whole stat line for a cosmetic change.

    - A relic's own completion bonus (:relic-bonus) is applied, and is rolled
      from the item's seed rather than taken flat from the record -- one with a
      base of 15 contributed 16. Forty of the game's relic completion bonuses
      grant a resistance, worth up to 25, so missing this is a large error on
      any character whose relic rolled one. Neither test character had one; it
      was found by putting one on deliberately.

    - A set bonus is not on any item. It lives on the set's own record, and its
      value is an array indexed by how many pieces are worn, so a set granting
      [0 25 25] gives nothing for one piece and 25 for two. Sixty-seven of the
      game's 253 sets grant a resistance, worth up to 25 each. Nothing on an item
      points at this: it is found only by looking the set up. Not one of the six
      characters this was developed against has a live set resistance bonus --
      every set they wear is a pet set -- so this was found by asking what the
      calculation does not read rather than by a figure disagreeing.

    - A skill granted by an item or component is not in the character's skill
      list at all -- only potion modifiers are -- so it is reached through the
      granting record's `itemSkillName`. Seal of Might grants Presence of Might,
      worth 12 Pierce, 12 Vitality and 12 Bleeding. Five of the six characters
      wear two Seals each, and it applies once: the two grant the same skill, and
      the record is capped at one level. Its classes divide the same way devotion
      stars do -- `Skill_Passive` and the toggled ones apply, while
      `Skill_BuffSelfDuration`, `Skill_PassiveOnLifeBuffSelf` and
      `Skill_Shapeshift` do not, being temporary, conditional on low health, or a
      transformation. Found because Mary read 78 where the game said 80, the only
      figure of forty across four fresh characters that disagreed.

    - A `SkillBuff_Debuf` reached through `buffSkillName` is aimed at enemies.
      Curse of Frailty's buff record carries `defensiveBleeding` from -8 to -55,
      and the path that collects a player's toggled auras would have subtracted
      it. No character here triggers it, but the record and the path both exist.

    - A blacksmith bonus on a relic is not applied at all. The same Poison
      Resistance bonus was put on a helm and then on a relic on the same
      character: the helm moved the figure by 4, the relic by nothing. That
      accounts for a crafted Chaos Resistance the game had been ignoring.

  The figures here are computed rather than read, so `resists` says so on screen
  and `resists all` prints the parts each total is made of -- a wrong answer is
  worth reporting, and arrives with enough detail to find the term at fault."
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

(defn- relic?
  [item]
  (= "ItemArtifact" (str (get (dbu/record-by-name (:basename item)) "Class"))))

(defn- as-worn
  "An item as the game actually treats it.

  A blacksmith bonus on a relic is not applied. Verified directly: the same
  Poison Resistance bonus moved a character's resistance by 4 on a helm and by
  nothing at all on that character's relic, and it accounts exactly for a relic's
  crafted Chaos Resistance that the game had been ignoring."
  [item]
  (cond-> item
    (relic? item) (assoc :modifier-name "")))

(defn- equipped
  "Every item the character is actually wearing.

  A weapon set that is not in use carries an :unused flag; its weapons are
  carried but contribute nothing."
  [character]
  (->> (concat (:equipment character)
               (->> (:weapon-sets character) (remove :unused) (mapcat :items)))
       (filter #(not-empty (str (:basename %))))
       (map as-worn)))

(defn- set-bonuses
  "The set bonus for each set the character has pieces of.

  A set's record holds one array per stat, indexed by pieces worn less one, so
  a three-piece set granting [0 25 25] gives nothing until the second piece.
  Pieces are counted by distinct base record: the set lists distinct members, so
  wearing two copies of the same ring is one piece, not two.

  Nothing on an item carries this -- the item names its set and the set holds the
  bonus -- so it is reached the long way round, from `itemSetName`."
  [items]
  (->> items
       (keep (fn [it] (when-let [s (some-> (:basename it) not-empty dbu/record-by-name
                                           (get "itemSetName") not-empty)]
                        [(str s) (str (:basename it))])))
       (reduce (fn [m [s base]] (update m s (fnil conj #{}) base)) {})
       (keep (fn [[s bases]]
               (when-let [record (dbu/record-by-name s)]
                 [record (count bases)])))))

(def ^:private granted-skill-classes
  "Classes of item-granted skill that are actually running.

  The toggled ones reserve energy and stay on until switched off. Left out are
  `Skill_BuffSelfDuration` (temporary), `Skill_PassiveOnLifeBuffSelf` (only below
  a health threshold) and `Skill_Shapeshift` (a transformation), for the same
  reason a temporary devotion buff is left out: they are not running."
  #{"Skill_Passive" "Skill_BuffSelfToggled" "Skill_BuffAttackRadiusToggled"
    "Skill_BuffRadiusToggled"})

(defn- granted-skills
  "Skills granted by the items, components and augments the character wears.

  These are not in the character's skill list -- only potion modifiers are -- so
  they are reached through the granting record's `itemSkillName`.

  Keyed by skill record, so the same buff granted by two components counts once.
  Five of the six characters this was checked against wear two Seals of Might,
  and Presence of Might is one buff however many grant it.

  Whether a toggled one is switched on is not written to the save, so it is taken
  as on. That is what the character sheet shows for a character who has it
  running, and it is the reading Mary's Bleeding agrees with."
  [items]
  (->> (for [it items
             k [:basename :relic-name :augment-name]
             :let [rec (some-> (get it k) not-empty str dbu/record-by-name)
                   skill (some-> rec (get "itemSkillName") not-empty str)
                   sr (some-> skill dbu/record-by-name)]
             :when (and sr (contains? granted-skill-classes (str (get sr "Class"))))]
         [skill sr])
       (into {})
       vals))

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
        ;; a SkillBuff_Debuf is what the skill does to an enemy, not to the player
        :when (and b (not (str/includes? (str (get b "Class")) "Debuf")))]
    [b (:level s)]))

(defn contributions
  "Resistance values and cap modifiers, kept separate by where they came from.

  Returns {:sources {source-key {field amount}} :caps {field amount}} so a
  caller can show the breakdown, which is what makes a wrong answer diagnosable."
  [character]
  (let [items (equipped character)
        gear (reduce (fn [m it] (add-values m (item-stats/rolled-stats it) nil)) {} items)
        attached (reduce (fn [m it]
                           (as-> m $
                             ;; the component and augment socketed into the item
                             ;; the component, the augment, and any ascended affix.
                             ;; Ascended affixes are flat: there is not one value in
                             ;; that data that rolls, so the record's number is the
                             ;; number -- one granting 3 Physical gave exactly 3.
                             (reduce (fn [m p]
                                       (add-values m (some-> (not-empty (str p)) dbu/record-by-name) nil))
                                     $ [(:relic-name it) (:augment-name it) (:ascended-name it)])
                             ;; and a relic's own completion bonus, which is rolled
                             ;; from the item's seed rather than being flat -- measured
                             ;; at 16 from a base of 15 on the character this was
                             ;; checked against, so the record value will not do
                             (let [vals (:values (item-stats/completion-bonus it))]
                               (reduce (fn [m f]
                                         (if-let [v (some-> (get vals f) double)]
                                           (update m f (fnil + 0.0) v)
                                           m))
                                       $ fields))))
                         {} items)
        sets (reduce (fn [m [record worn]] (add-values m record worn))
                     {} (set-bonuses items))
        granted (reduce (fn [m r] (add-values m r nil)) {} (granted-skills items))
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
               (reduce (fn [m r] (add-caps m r nil)) $ (granted-skills items))
               (reduce (fn [m s] (add-caps m (dbu/record-by-name (:skill-name s)) (:level s)))
                       $ (passive-devotions character))
               (reduce (fn [m [r lvl]] (add-caps m r lvl)) $ (active-buffs character)))]
    {:sources {:gear gear :attachments attached :sets sets :item-skills granted
               :devotions devo :auras buffs}
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
