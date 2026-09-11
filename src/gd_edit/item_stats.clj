(ns gd-edit.item-stats
  "Real, seed-applied item stats.

  An item's save record stores only what it is made of -- basename, prefix,
  suffix and a seed -- never the numbers a player sees. A base record holds each
  stat's unrolled value, and the game turns that into the displayed figure at
  load time by applying the seed. So a summary built from the records alone
  reports the unrolled value: gd-edit showed +60% Cold Damage for an item the
  game reads as +89%.

  Rolling the values ourselves needs the game's own draw order, which is supplied
  by marius00's Grim Dawn Item Stats engine:

      https://github.com/marius00/GrimDawnItemStats

  Its sources are not in this repository -- drop them under java-src/ and the
  build compiles them in. That engine is optional: it is resolved reflectively at
  runtime and every entry point here returns nil when it is absent, so gd-edit
  runs unchanged without it and simply shows the unrolled values as it always
  did. See packaging/common/THIRD-PARTY.txt for the terms it is published under."
  (:require [gd-edit.db-utils :as dbu]))

(def ^:private pet-calculator
  "The engine's calculatePetBonus method, or nil when the engine is absent."
  (delay
    (try
      (let [c (Class/forName "com.grimdawn.itemstats.Calculator")]
        (.getMethod c "calculatePetBonus"
                    (into-array Class [java.util.Map java.util.Map java.util.Map
                                       java.util.Map java.util.Map Long/TYPE])))
      (catch Throwable _
        nil))))

(def ^:private calculator
  "The engine's calculate method, or nil when the engine is not on the classpath.

  Resolved reflectively and once. A hard import would make this namespace fail
  to load without the engine, and since the vendored sources are deliberately
  not committed, that would break every build that does not have them."
  (delay
    (try
      (let [c (Class/forName "com.grimdawn.itemstats.Calculator")]
        (.getMethod c "calculate"
                    (into-array Class [java.util.Map java.util.Map java.util.Map
                                       Long/TYPE java.util.Map])))
      (catch Throwable _
        nil))))

(defn available?
  "True when the stat engine can be used."
  []
  (some? @calculator))

(defn- record->stat-map
  "A game record as the field -> value string map the engine expects.

  Records carry a :recordname keyword alongside their string-keyed fields; only
  the latter are stat data. Values are stringified because the engine parses
  them itself, exactly as they appear in the game database."
  [record]
  (when record
    (java.util.HashMap.
     ^java.util.Map
     (into {} (for [[k v] record :when (string? k)]
                [k (str v)])))))

(defn- unsigned-seed
  "The seed as the engine wants it: unsigned, in a long.

  Seeds are held as a signed int32 in the save file because that is the width
  the format uses, but the game reads them as unsigned -- so the negative ones
  must be widened rather than sign-extended."
  [seed]
  (Integer/toUnsignedLong (int seed)))

(defn rolled-stats
  "Field -> rolled value for `item`, or nil if it cannot be computed.

  Returns nil rather than throwing for anything unexpected: a missing engine, a
  basename with no matching record, or a record the engine chokes on. A summary
  that silently falls back to ranges is a great deal better than one that cannot
  be printed at all."
  [item]
  (when-let [calc @calculator]
    (try
      (when-let [base (dbu/record-by-name (:basename item))]
        (let [affix (fn [k]
                      (let [n (get item k)]
                        (when-not (or (nil? n) (= "" n))
                          (record->stat-map (dbu/record-by-name n)))))
              entries (.invoke calc nil
                               (object-array
                                [(record->stat-map base)
                                 (affix :prefix-name)
                                 (affix :suffix-name)
                                 (unsigned-seed (:seed item))
                                 (affix :modifier-name)]))]
          (into {} (for [e entries]
                     [(.get (.getField (class e) "field") e)
                      (.get (.getField (class e) "value") e)]))))
      (catch Throwable _
        nil))))

(defn with-rolled-stats
  "`record` with its stat fields replaced by the values `item`'s seed rolls.

  The base record holds each stat's unrolled value -- a sword's record says
  `offensiveColdModifier 60` where the item in game reads +89%. The game applies
  the seed to that base, so substituting the rolled values into the record makes
  every existing formatter print what the player will actually see, without any
  of them knowing a seed was involved.

  Values are rounded because that is what the game displays: a roll of 17.156
  reads as 17% in the tooltip. Of the 54 values checked against real items only
  one was fractional at all, and rounding is what made it agree.

  Except where the record's own value is fractional, in which case the fraction
  is the stat rather than noise in the roll. A shield blocks in 0.65 seconds and
  the game says so; rounding read it as 1. The record decides: a whole base is a
  stat the game shows whole, so its roll is rounded, while a fractional base --
  block recovery, energy regeneration, the attack speed on a weapon -- keeps
  what it rolled. A conversion is the case that proves the default, its base a
  whole 25 against a roll of 25.137 and a tooltip reading 25%.

  Returns the record untouched when the stats cannot be computed, which is what
  makes the whole feature optional."
  [record item]

  (if-let [rolled (rolled-stats item)]
    (let [whole? (fn [x] (and (number? x)
                              (== (double x) (double (Math/round (double x))))))]
      (merge record (into {} (for [[k v] rolled]
                               [k (if (whole? (get record k))
                                    (double (Math/round ^double v))
                                    (double v))]))))
    record))

;; ------------------------------------------------- completion bonuses
;;
;; A relic's completion bonus is not stored as a number. The save names a bonus
;; record, and the value is rolled from it -- from the relic's *own* stream,
;; primed with the item's seed, one draw per field in the engine's draw order.
;;
;; That was established by prediction rather than fitting: the model was derived
;; from Mogdrogen's Ardor, then used to predict Aurora's three values (4/3/4)
;; before they were looked at. All four values across the two relics match the
;; game exactly.
;;
;; Note the jitter comes from the bonus record's own lootRandomizerJitter, not
;; the 20% a base record gets. Ardor settles that: 50% gives the [13-37] the
;; game shows, 20% would give [20-30].

(defn- draw-order
  "The order the engine rolls a record's fields in.

  Read off the engine rather than guessed at. The values it returns are wrong
  for this purpose -- it applies the base-record jitter rather than the record's
  own -- so only the ordering is taken."
  [recordname]
  (keys (rolled-stats {:basename recordname :seed 1
                       :prefix-name "" :suffix-name "" :modifier-name ""})))

(defn- minstd-step ^long [^long s]
  (let [hi (quot s 127773) lo (rem s 127773)
        r (- (* 16807 lo) (* 2836 hi))]
    (if (< r 0) (+ r 2147483647) r)))

(defn- bonus-roll
  "One field's value: base jittered by `draw`, matching the engine's rules."
  ^double [^long draw ^double base ^double jitter]
  (if (or (zero? base) (zero? jitter))
    base
    (let [spread (let [x (long (* base jitter 0.01))] (if (zero? x) 1 x))
          modulus (inc (* 2 spread))
          v (+ (- (double (rem draw modulus)) spread) base)]
      (if (< (Math/abs v) 1.0) base v))))

(defn display-round
  "Round a range endpoint to something fit to print.

  A conversion's range is worked out multiplicatively, and the engine narrows
  the factor to a 32-bit float on purpose -- that narrowing is what makes a
  rolled value match the game. It also leaves an error of that size behind, so
  a base of 30 at the bottom of its jitter comes out 24.00000035762787 rather
  than 24. Harmless inside the engine; in a tooltip it reads as
  [24.00000035762787-36.000001430511475].

  Four decimal places is far finer than anything the database distinguishes and
  far coarser than the error, so it removes the noise and leaves a genuinely
  fractional range alone."
  ^double [^double x]
  (/ (Math/rint (* x 10000.0)) 10000.0))

(defn- rolled-range
  "The [low high] a field can roll to, given its base and the record's jitter.

  Conversions are the exception: they roll multiplicatively and the engine
  clamps them, because nothing converts more than all of a damage type. Treating
  them like an ordinary spread prints ranges that reach past 100, which cannot
  happen."
  [field ^double base ^double jitter]
  (if (re-find #"(?i)^conversionPercentage" (str field))
    (let [j (* jitter 0.01)]
      [(display-round (max 0.0 (* base (- 1.0 j))))
       (display-round (min 100.0 (* base (+ 1.0 j))))])
    (let [spread (let [x (long (* base jitter 0.01))] (if (zero? x) 1 x))]
      [(- base spread) (+ base spread)])))

(defn completion-bonus
  "The rolled values and ranges of `item`'s completion bonus.

  Returns {:values {field v} :ranges {field [lo hi]}}, or nil when the item has
  no completion bonus or the engine is unavailable."
  [item]
  (when (available?)
    (let [rec (some-> (:relic-bonus item) not-empty dbu/record-by-name)]
      (when rec
        (let [jitter (double (or (get rec "lootRandomizerJitter") 0.0))
              seed   (unsigned-seed (:seed item))]
          (loop [fs (draw-order (:recordname rec))
                 s  (minstd-step (minstd-step seed))
                 out {:values {} :ranges {}}]
            (if (empty? fs)
              out
              (let [f (first fs)
                    base (double (get rec f 0.0))
                    spread (let [x (long (* base jitter 0.01))] (if (zero? x) 1 x))]
                (recur (rest fs)
                       (minstd-step s)
                       (-> out
                           (assoc-in [:values f] (double (Math/round (bonus-roll s base jitter))))
                           (assoc-in [:ranges f] (rolled-range f base jitter))))))))))))

;; ------------------------------------------------------- pet bonuses
;;
;; An item that helps pets names a pet bonus record, whose stats roll from the
;; item's seed on a stream of their own -- the same arrangement as a completion
;; bonus, and the engine implements it directly.

(defn pet-bonus
  "The rolled values and ranges of `item`'s Bonus to All Pets, or nil.

  `record` is the item's base record, already resolved by the caller."
  [record item]
  (when-let [calc @pet-calculator]
    (try
      (when-let [pet (some-> (get record "petBonusName") not-empty dbu/record-by-name)]
        (let [entries (.invoke calc nil
                               (object-array
                                [(record->stat-map pet) nil nil nil nil
                                 (unsigned-seed (:seed item))]))
              jitter 20.0]
          {:values (into {} (for [e entries]
                              [(.get (.getField (class e) "field") e)
                               (double (Math/round ^double (.get (.getField (class e) "value") e)))]))
           :ranges (into {} (for [[k v] pet
                                  :when (and (string? k) (number? v) (pos? (double v)))
                                  :let [base (double v)]]
                              [k
                               (rolled-range k base jitter)]))}))
      (catch Throwable _
        nil))))

(defn modifier-ranges
  "field -> [low high] for the fields an item's blacksmith bonus contributes.

  A crafted item's bonus rolls like anything else, but from its own record and
  its own jitter, and the fitted plan never sees it -- plans are fitted for the
  item alone, which is also the configuration find-seed creates. Without this
  such a stat prints its value and no range, which looks like an oversight
  rather than the deliberate omission it would otherwise be."
  [item]
  (when-let [m (some-> (:modifier-name item) not-empty dbu/record-by-name)]
    (let [jitter (double (or (get m "lootRandomizerJitter") 0.0))]
      (when (pos? jitter)
        (into {} (for [[k v] m
                       :when (and (string? k) (number? v) (pos? (double v))
                                  (not= k "lootRandomizerJitter")
                                  (not= k "levelRequirement")
                                  (not= k "lootRandomizerCost")
                                  (not= k "marketAdjustmentPercent"))]
                   [k (rolled-range k (double v) jitter)]))))))
