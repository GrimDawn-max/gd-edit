(ns gd-edit.max-rolls
  "Finding the seed that makes an item roll as high as it can.

  `find-seed` does this interactively for one item at a time. `make-char` needs
  the same answer for a dozen items without asking anything, so the part that
  decides which seed is best lives here rather than in either command -- two
  copies of this would drift, and the interesting subtlety (that pet and
  completion bonuses roll on their own streams and have to be scored alongside
  the item's own stats) is easy to get wrong in only one of them."
  (:require [gd-edit.db-utils :as dbu]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.seed-search :as ss]))

(defn pet-aux
  "Scoring arrays for an item's Bonus to All Pets, or nil if it has none.

  A pet bonus rolls from the same seed on a stream of its own and is
  uncorrelated with the item's own roll, so a search that ignored it would leave
  it to chance -- and chance does badly."
  [record]
  (when-let [pet (some-> (get record "petBonusName") not-empty dbu/record-by-name)]
    (let [order (map first (:values (item-stats/pet-bonus
                                     record
                                     {:basename (:recordname record) :seed 1})))]
      (ss/aux-stream pet order 20.0))))

(defn completion-aux
  "Scoring arrays for a relic's completion bonus, or nil.

  Unlike a component's, a relic's completion bonus is stored on the item, so we
  know which one it is and can score it."
  [item]
  (when-let [bonus (some-> (:relic-bonus item) not-empty dbu/record-by-name)]
    (let [jitter (double (or (get bonus "lootRandomizerJitter") 0.0))
          order (map first (:values (item-stats/completion-bonus
                                     (assoc item :seed 1))))]
      (when (seq order)
        (ss/aux-stream bonus order jitter)))))

(defn best-seed
  "The seed that gets `item`'s stats as high as they go, or nil.

  Returns nil rather than throwing whenever the answer cannot be worked out --
  no stat engine, an unknown basename, an item that rolls nothing. Callers are
  expected to leave the existing seed alone in that case, which is always a
  valid item, just an average one.

  The item's blacksmith bonus is part of the plan because it rolls from the same
  stream and shifts every draw after it; components and augments are not,
  because they contribute fixed values and cannot be affected by the seed."
  [item]
  (try
    (when (item-stats/available?)
      (when-let [record (dbu/record-by-name (:basename item))]
        (when-let [plan (ss/fit-plan record (some-> (:modifier-name item) not-empty))]
          (when (seq (ss/searchable-fields plan))
            (let [auxes (vec (remove nil? [(pet-aux record) (completion-aux item)]))
                  compiled (cond-> (ss/compile-best plan)
                             (seq auxes) (assoc :aux auxes))]
              (first (ss/search-best compiled 1)))))))
    (catch Throwable _ nil)))

(defn maximise
  "Replace `item`'s seed with the best one, leaving it untouched if none is found."
  [item]
  (if-let [s (best-seed item)]
    (assoc item :seed s)
    item))
