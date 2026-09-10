(ns gd-edit.character-stats
  "A character's attributes and combat stats, as the game shows them.

  None of these are stored in a save. The game computes them on load, and this
  reproduces that computation from the save and the game's own database. Where
  the game ships an equation, it is used rather than reverse engineered:
  `records/game/combatformulas.dbr` holds the offensive and defensive ability
  equations verbatim, and `records/creatures/pc/malepc01.dbr` holds the constants
  every character starts with -- 250 life, 250 mana, 65 offensive and defensive
  ability, 50 in each attribute.

  Every figure here was checked against the character sheet in game, including
  the per-slot armour breakdown and the amount by which a capped resistance
  exceeds its cap. Six things were found that way, each of which had produced a
  confident wrong answer first:

    - The game computes in 32-bit floats and truncates. HotFluffy's Spirit is
      760 x 1.05, which is exactly 798 in double precision but 797.99994 in
      single, and the sheet reads 797. Five separate figures were each wrong by
      exactly one until this was applied, so `f32` wraps every arithmetic step
      rather than only the result.

    - A skill's level is not the level in the save; gear raises it. See
      `gd-edit.resistances`, which needs the same thing.

    - Mastery bars grant attributes. Each is a Skill_Mastery record holding a
      hundred-entry array per stat, and ColdFluffy's two bars at 50 are worth
      325 Physique, 300 Cunning and 375 Spirit -- exactly the three amounts its
      attributes were short by.

    - Health comes from all three attributes, not just Physique: 2.5 per point of
      Physique above 50, 1.5 per point of Spirit, 1.0 per point of Cunning. Only
      the in-game tooltips say so; nothing in the database does. Trying to make
      Physique alone account for all three is why no constant would fit.

    - Health starts from the template's 250, not from the save's `:health`. The
      saved value is what the sheet prints in white beside the bonus, but it is
      not what the calculation begins with: subtracting the attribute bonuses,
      the leftover error on four characters came to 2180, 2012, 1904 and 1247,
      which are precisely each one's saved health less 250. ColdFluffy's saved
      health happens to equal 250 + 2.5 * (its base Physique - 50) to the point,
      which is a coincidence that held on one character out of six.

    - Energy is reserved, not spent. The sheet shows 1486 / 2678 for a character
      whose energy never refills past 1486, because toggled buffs reserve it
      through `characterManaLimitReserve`. Two Seals of Might reserve 600, which
      independently confirms that item-granted buffs stack per component -- a
      conclusion first reached from resistances, by a wholly unrelated
      measurement."
  (:require [gd-edit.db-utils :as dbu]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.resistances :as res]))

(defn f32
  "One arithmetic step, rounded to 32-bit float as the game would."
  ^double [x]
  (double (Float/intBitsToFloat (Float/floatToIntBits (float x)))))

(defn- shown
  "What the sheet prints: the game truncates rather than rounds."
  ^long [x]
  (long x))

(def ^:private base-life 250.0)
(def ^:private base-energy 250.0)
(def ^:private base-ability 65.0)
(def ^:private base-attribute 50.0)

(def ^:private health-per-attribute
  "Health each attribute grants per point above its starting 50.

  From the in-game tooltips, which report it directly as \"Bonus Health\";
  nothing in the database carries these rates. Verified against ten tooltip
  figures across five characters."
  {:physique 2.5 :spirit 1.5 :cunning 1.0})

(def ^:private armour-slots
  "The equipment slots that carry armour, and the chance the game gives each of
  being hit. They sum to 100, and the rating is their weighted average.

  A belt is armour but has no hit location, so it contributes to every slot
  instead -- which is what the sheet means by \"bonuses on skills and on
  non-armor pieces are added to all armor slots\"."
  {0 ["Head" 15] 9 ["Shoulders" 15] 2 ["Chest" 26] 5 ["Arms" 12] 3 ["Legs" 20] 4 ["Feet" 12]})

(defn- totals
  "Every field this namespace needs, summed over the character's sources."
  [character]
  (apply merge-with +
         {}
         (for [[_ sources] (res/stat-sources character)]
           (res/collect sources
                        ["characterStrength" "characterDexterity" "characterIntelligence"
                         "characterStrengthModifier" "characterDexterityModifier"
                         "characterIntelligenceModifier"
                         "characterLife" "characterLifeModifier"
                         "characterMana" "characterManaModifier"
                         "characterManaLimitReserve" "characterManaLimitReserveReduction"
                         "characterOffensiveAbility" "characterDefensiveAbility"
                         "characterOffensiveAbilityModifier" "characterDefensiveAbilityModifier"
                         "defensiveProtection" "defensiveProtectionModifier"]))))

(defn- with-modifier
  "`base` raised by a percentage, a step at a time in single precision."
  ^double [base pct]
  (f32 (* (f32 base) (f32 (+ 1.0 (f32 (/ pct 100.0)))))))

(defn- bonus-protection
  "Armour a component or augment adds to the piece it is socketed in.

  Unlike `defensiveProtection`, which is shared across every slot, this belongs
  to its own piece: it accounted for exactly the 32, 35 and 24 by which
  RedPriest's head, legs and arms were short."
  ^double [item]
  (reduce + 0.0
          (for [k [:relic-name :augment-name]
                :let [record (some-> (get item k) not-empty str dbu/record-by-name)
                      v (some-> record (get "defensiveBonusProtection"))]
                :when (number? v)]
            (double v))))

(defn- slot-protection
  ^double [item]
  (double (or (get (item-stats/rolled-stats item) "defensiveProtection") 0.0)))

(defn compute
  "Every attribute and combat stat for `character`.

  Armour is returned both as the rating the sheet shows and per slot, since the
  sheet breaks it down that way and a wrong total is far easier to place when
  the six slots are visible."
  [character]
  (let [t (totals character)
        g (fn ^double [f] (double (get t f 0.0)))
        equipment (:equipment character)
        level (double (:character-level character))

        physique (with-modifier (+ (:physique character) (g "characterStrength"))
                                (g "characterStrengthModifier"))
        cunning (with-modifier (+ (:cunning character) (g "characterDexterity"))
                               (g "characterDexterityModifier"))
        spirit (with-modifier (+ (:spirit character) (g "characterIntelligence"))
                              (g "characterIntelligenceModifier"))

        from-attribute (fn ^double [k v] (* (get health-per-attribute k)
                                            (- v base-attribute)))
        health (with-modifier (+ base-life
                                 (from-attribute :physique physique)
                                 (from-attribute :cunning cunning)
                                 (from-attribute :spirit spirit)
                                 (g "characterLife"))
                              (g "characterLifeModifier"))

        ;; energy follows spirit at 2 per point, on top of the template's 250
        energy (* (+ base-energy (* 2.0 (- spirit base-attribute)) (g "characterMana"))
                  (f32 (+ 1.0 (f32 (/ (g "characterManaModifier") 100.0)))))
        reserved (- (g "characterManaLimitReserve") (g "characterManaLimitReserveReduction"))

        ability (fn ^double [flat pct attribute]
                  (f32 (+ (with-modifier (+ base-ability (g flat) (* level 12.0) (* attribute 0.5))
                                         (g pct))
                          53.0)))
        offensive (ability "characterOffensiveAbility" "characterOffensiveAbilityModifier" cunning)
        defensive (ability "characterDefensiveAbility" "characterDefensiveAbilityModifier" physique)

        ;; armour not worn on one of the six slots is shared across all of them
        shared (- (g "defensiveProtection")
                  (reduce + 0.0 (map #(slot-protection (nth equipment %)) (keys armour-slots))))
        exact (into {} (for [[i [label _]] armour-slots]
                         [label (with-modifier (+ (slot-protection (nth equipment i))
                                                  (bonus-protection (nth equipment i))
                                                  shared)
                                               (g "defensiveProtectionModifier"))]))]
    {:physique (shown physique)
     :cunning (shown cunning)
     :spirit (shown spirit)
     :health (shown health)
     :energy (shown energy)
     :energy-usable (shown (- energy reserved))
     :energy-reserved (shown reserved)
     :offensive-ability (shown offensive)
     :defensive-ability (shown defensive)
     ;; the sheet rounds each slot for display but weights the exact values
     :armour (shown (reduce + 0.0 (for [[_ [label chance]] armour-slots]
                                    (* (/ chance 100.0) (get exact label)))))
     :armour-by-slot (into {} (for [[_ [label _]] armour-slots]
                                [label (Math/round (double (get exact label)))]))}))
