(ns gd-edit.seed-search
  "Finding a seed that rolls the item stats you want.

  An item's numbers are decided by its seed, so asking for a specific roll means
  searching the seed space for one that produces it. There are 2^32 seeds, and
  the reference engine computes about 100k of them a second -- half a day for a
  full sweep -- because it is built to be right about one item rather than fast
  about four billion.

  What makes a fast search possible is that for a fixed item almost everything
  the engine does is invariant. The same fields roll, in the same order, from
  the same base values, every time; only the random stream differs. So the work
  splits in two: derive a plan for the item once, then run a tiny loop over the
  seeds.

  The plan is not transcribed from the engine, it is *fitted to it*. Each field
  is matched against the engine's own output until one model, draw position and
  scaling rule explains every sample, and the finished plan is checked again on
  seeds it was never fitted to. A field nothing explains is recorded as
  unfittable and simply cannot be searched on -- it does not invalidate the rest,
  because every other field's position was confirmed against the engine
  independently. That conservatism is the point: a search built on a draw order
  that is subtly wrong returns seeds that look perfectly reasonable and are not,
  and nobody would notice until the item turned up wrong in game."
  (:require [gd-edit.db-utils :as dbu]
            [gd-edit.item-stats :as item-stats]))

;; ---------------------------------------------------------------- the stream

(def ^:const MINSTD-A 16807)
(def ^:const MINSTD-Q 127773)
(def ^:const MINSTD-R 2836)
(def ^:const MINSTD-M 2147483647)

(defn step
  "One step of the game's MINSTD generator, by Schrage's method to stay in 32 bits."
  ^long [^long s]
  (let [hi (quot s MINSTD-Q)
        lo (rem s MINSTD-Q)
        r  (- (* MINSTD-A lo) (* MINSTD-R hi))]
    (if (< r 0) (+ r MINSTD-M) r)))

(defn first-draw
  "The generator's first output for `seed`.

  The engine's constructor already advances the state once, so the first draw is
  two steps in, not one."
  ^long [^long seed]
  (step (step seed)))

;; ------------------------------------------------------------- the roll rules

(defn spread-of
  "The half-width of a value's jitter, matching the engine's integer truncation."
  ^long [^double base ^double jitter-pct]
  (let [s (long (* base jitter-pct 0.01))]
    (if (zero? s) 1 s)))

(defn jittered
  "The value `draw` produces for a stat, before any scaling.

  A roll landing within one of zero is discarded and the base kept, which is what
  stops a small stat from rolling away to nothing."
  ^double [^long draw ^double base ^long spread ^long modulus]
  (let [v (+ (- (double (rem draw modulus)) spread) base)]
    (if (< (Math/abs v) 1.0) base v)))

(defn scaled-value
  "A jittered value after the item's attribute scaling.

  Deliberately single precision then truncated: the game computes it that way and
  the difference shows up in the last digit."
  ^double [^double v ^double scale-pct]
  (double (int (/ (double (float (* (float v) (float (+ 100.0 scale-pct))))) 100.0))))

(defn trunc ^double [^double x] (double (long x)))

(defn converted
  "A conversion percentage rolled from `draw`, clamped to nothing-through-all."
  ^double [^long draw ^double base ^double jitter-pct]
  (if (<= jitter-pct 0.0)
    base
    (let [j (* jitter-pct 0.01)
          factor (float (+ (* draw (Math/pow 2.0 -31) (* 2.0 j)) (- 1.0 j)))
          rolled (* base (double factor))]
      (cond (< rolled 0.0) 0.0
            (> rolled 100.0) 100.0
            :else rolled))))

;; ------------------------------------------------------------------ the models
;;
;; Each model says how one field's value is produced from the stream. They are
;; tried in turn against the engine's output; the first that explains every
;; sample wins. Model ids are kept as small ints because the search loop
;; switches on them per seed and must not touch a map.

(def ^:const MODEL-PLAIN 0)   ; one draw, jittered around a base
(def ^:const MODEL-SCALED 1)  ; ... then scaled by the item's attribute scaling
(def ^:const MODEL-PAIRMAX 2) ; a scaled min, plus a second draw of the min->max spread
(def ^:const MODEL-CONV 3)    ; a conversion percentage, its own jitter and clamping
(def ^:const MODEL-PAIRMAX-PLAIN 4) ; ... as PAIRMAX, where the min is not scaled

(defn value-of
  "The value `entry` predicts, given the draws it consumes."
  ^double [entry ^double scale ^long d1 ^long d2]
  (let [{:keys [model base spread modulus base2 spread2 modulus2 jitter]} entry
        m (long model)]
    (cond
      (== m MODEL-PLAIN)  (jittered d1 base spread modulus)
      (== m MODEL-SCALED) (scaled-value (jittered d1 base spread modulus) scale)
      (== m MODEL-CONV)   (converted d1 base jitter)
      (== m MODEL-PAIRMAX)
      (trunc (+ (scaled-value (jittered d1 base spread modulus) scale)
                (jittered d2 base2 spread2 modulus2)))
      (== m MODEL-PAIRMAX-PLAIN)
      (trunc (+ (jittered d1 base spread modulus)
                (jittered d2 base2 spread2 modulus2)))
      :else Double/NaN)))

;; ------------------------------------------------------------------- fitting

(def ^:private fit-seeds
  "Seeds a plan is fitted on. Fixed, so fitting is reproducible."
  (range 1 201))

(def ^:private check-seeds
  "Seeds a plan is checked on. Disjoint from the fitting seeds -- a plan that only
  explains the data it was fitted to explains nothing."
  (range 500000 500200))

(def ^:private max-draw
  "How far into the stream to look for a field's draw. Items roll a few dozen
  stats at most; this is slack, not a limit anyone should reach."
  128)

(def ^:private base-jitter 20.0)

(defn- engine-values
  ([recordname seed] (engine-values recordname seed "" nil nil))
  ([recordname seed modifier] (engine-values recordname seed modifier nil nil))
  ([recordname seed modifier prefix suffix]
   (item-stats/rolled-stats {:basename recordname :seed seed
                             :prefix-name (or prefix "") :suffix-name (or suffix "")
                             :modifier-name (or modifier "")})))

(defn- draws-for
  [^long seed ^long n]
  (loop [i 0, s (first-draw seed), acc (transient [])]
    (if (>= i n)
      (persistent! acc)
      (recur (inc i) (step s) (conj! acc s)))))

(defn- jitter-of
  "How widely a record's values roll.

  The base item has no lootRandomizerJitter and uses the engine's default. An
  affix carries its own -- 15% on one prefix, 12% on the suffix beside it -- so
  fitting an affix's stat with the item's spread tests the wrong hypotheses and
  explains nothing."
  ^double [record]
  (let [j (get record "lootRandomizerJitter")]
    (if (number? j) (double j) base-jitter)))

(defn- sum-candidates
  "Hypotheses for a field two records both contribute to.

  The engine rolls each contribution separately and adds them, so no single draw
  explains the total. Two adjacent draws can: that is what the PAIRMAX models
  already compute, and they only ever paired a record with itself. Pairing across
  the two records -- each with its own base and its own jitter -- is the same
  arithmetic applied to the case the fitter was blind to.

  Both orderings are offered because which contribution is drawn first is not
  something we get to assume. A hypothesis still has to explain every sample, and
  the finished plan is checked again on seeds it never saw, so a wrong pairing is
  rejected rather than believed."
  [ra rb field]
  (let [ba (get ra field), bb (get rb field)]
    (when (and (number? ba) (number? bb))
      (for [[r1 b1 r2 b2] [[ra ba rb bb] [rb bb ra ba]]
            :let [j1 (jitter-of r1), j2 (jitter-of r2)
                  b1 (double b1), b2 (double b2)
                  sp1 (spread-of b1 j1), sp2 (spread-of b2 j2)]
            model [MODEL-PAIRMAX-PLAIN MODEL-PAIRMAX]
            k (range 1 max-draw)]
        {:field field :model model :draw k :jitter j1
         :base b1 :spread sp1 :modulus (inc (* 2 sp1))
         :base2 b2 :spread2 sp2 :modulus2 (inc (* 2 sp2))}))))

(defn- candidates
  "Every (model, draw) hypothesis worth testing for `field`."
  [record field ^double base]
  (let [jit     (jitter-of record)
        spread  (spread-of base jit)
        modulus (inc (* 2 spread))
        common  {:field field :base base :spread spread :modulus modulus
                 :jitter jit}
        pair    (when (and (string? field) (.endsWith ^String field "Max"))
                  (let [stem (subs field 0 (- (count field) 3))
                        bmin (get record (str stem "Min"))]
                    (when bmin
                      (let [bmin (double bmin)
                            span (max 0.0 (- base bmin))
                            sp2  (spread-of span jit)]
                        {:base bmin
                         :spread (spread-of bmin jit)
                         :modulus (inc (* 2 (spread-of bmin jit)))
                         :base2 span :spread2 sp2 :modulus2 (inc (* 2 sp2))}))))]
    (concat
     (for [k (range 1 (inc max-draw))] (assoc common :model MODEL-PLAIN :draw k))
     (for [k (range 1 (inc max-draw))] (assoc common :model MODEL-SCALED :draw k))
     (for [k (range 1 (inc max-draw))] (assoc common :model MODEL-CONV :draw k))
     (when pair
       (for [k (range 1 max-draw)]
         (merge common pair {:model MODEL-PAIRMAX :draw k})))
     (when pair
       (for [k (range 1 max-draw)]
         (merge common pair {:model MODEL-PAIRMAX-PLAIN :draw k}))))))

(defn fit-plan
  "Work out how `record`'s stats are rolled.

  Returns {:scale :entries :fixed :unfittable}. Entries carry everything the
  search loop needs, so the loop never touches a record or a map."
  ([record] (fit-plan record nil nil nil))
  ([record modifier] (fit-plan record modifier nil nil))
  ([record modifier prefix suffix]
   (let [recordname (:recordname record)
        scale   (double (or (get record "attributeScalePercent") 0.0))
        ;; Fitted for the item as it will actually be built. A blacksmith bonus
        ;; consumes draws of its own and shifts every stat after it, so a plan
        ;; fitted without one describes a different item. The same is true of a
        ;; prefix and a suffix, and most of an affixed item's value can live in
        ;; them -- on a pendant with both, only one of seven rolled stats came
        ;; from the item itself.
        modrec  (when (seq (str modifier)) (dbu/record-by-name modifier))
        prerec  (when (seq (str prefix)) (dbu/record-by-name prefix))
        sufrec  (when (seq (str suffix)) (dbu/record-by-name suffix))
        samples (vec (for [seed fit-seeds]
                       {:draws (draws-for seed max-draw)
                        :stats (engine-values recordname seed modifier prefix suffix)}))
        fields  (sort (distinct (mapcat (comp keys :stats) samples)))
        fits?   (fn [entry]
                  (let [k (long (:draw entry))]
                    (every? (fn [s]
                              (let [d1 (nth (:draws s) (dec k))
                                    d2 (nth (:draws s) k 0)
                                    want (get (:stats s) (:field entry))]
                                (and want (== (value-of entry scale d1 d2) (double want)))))
                            samples)))]
    (reduce
     (fn [plan field]
       (let [;; Which record supplies this field's base value? Exactly one must,
             ;; because the engine sums the contributions and a sum cannot be
             ;; explained by a single draw -- a field two records both define is
             ;; left unfittable rather than fitted to something plausible and
             ;; wrong.
             sources (filterv #(and % (contains? % field)) [record prerec sufrec modrec])
             collision? (> (count sources) 1)
             record (or (first sources) record)
             observed (map #(get (:stats %) field) samples)
             ;; A stat with a single value carries only a Min on the record, yet
             ;; the engine still reports a Max -- the same number. Rolling that
             ;; Max from the Min's base is what makes those fields explainable
             ;; instead of merely unexplained.
             base     (or (get record field)
                          (when (and (string? field) (.endsWith ^String field "Max"))
                            (get record (str (subs field 0 (- (count field) 3)) "Min"))))]
         (cond
           (apply = observed)
           (update plan :fixed assoc field (double (first observed)))

           collision?
           (if-let [hit (first (filter fits? (sum-candidates (first sources) (second sources) field)))]
             (update plan :entries conj hit)
             (update plan :unfittable conj field))

           (nil? base)
           (update plan :unfittable conj field)

           :else
           (if-let [hit (first (filter fits? (candidates record field (double base))))]
             (update plan :entries conj hit)
             (update plan :unfittable conj field)))))
     {:scale scale :entries [] :fixed {} :unfittable []
      :recordname recordname :modifier modifier :prefix prefix :suffix suffix}
     fields))))

(defn plan-valid?
  "Whether every fitted entry still predicts the engine on unseen seeds.

  Unfittable fields are not consulted: they are excluded from searching rather
  than held against the fields we did explain."
  [plan]
  (let [{:keys [scale entries fixed recordname]} plan]
    (every?
     (fn [seed]
       (let [actual (engine-values recordname seed (:modifier plan) (:prefix plan) (:suffix plan))
             draws  (draws-for seed max-draw)]
         (and (every? (fn [[f v]] (== (double v) (double (get actual f 0)))) fixed)
              (every? (fn [e]
                        (let [k (long (:draw e))
                              d1 (nth draws (dec k))
                              d2 (nth draws k 0)]
                          (== (value-of e scale d1 d2) (double (get actual (:field e) 0)))))
                      entries))))
     check-seeds)))

(defn stat-range
  "The [low high] an entry can roll, which is what a chooser should show."
  [plan entry]
  (let [scale (double (:scale plan))
        {:keys [model base spread base2 spread2 jitter]} entry]
    (cond
      (== (long model) MODEL-CONV)
      [(converted 0 base jitter) (converted MINSTD-M base jitter)]

      (== (long model) MODEL-PAIRMAX)
      [(trunc (+ (scaled-value (- base spread) scale) (max 0.0 (- base2 spread2))))
       (trunc (+ (scaled-value (+ base spread) scale) (+ base2 spread2)))]

      (== (long model) MODEL-PAIRMAX-PLAIN)
      [(trunc (+ (- base spread) (max 0.0 (- base2 spread2))))
       (trunc (+ (+ base spread) (+ base2 spread2)))]

      (== (long model) MODEL-SCALED)
      [(scaled-value (- base spread) scale) (scaled-value (+ base spread) scale)]

      :else
      [(- base spread) (+ base spread)])))

;; -------------------------------------------------------------------- search

(defn searchable-fields
  "Fields a caller may set a minimum on, with their ranges."
  [plan]
  (into {} (for [e (:entries plan)]
             [(:field e) (stat-range plan e)])))

(defn compile-search
  "A plan plus the caller's minimums, flattened into primitive arrays.

  The search loop runs billions of times, so nothing in it may touch a map: a
  keyword lookup per stat per seed costs more than all the arithmetic together.
  Only constrained fields are checked, but the stream still has to be advanced
  past the others, so entries stay in draw order."
  [plan minimums]
  (let [wanted (->> (:entries plan)
                    (filter #(contains? minimums (:field %)))
                    (sort-by :draw)
                    vec)
        dbl (fn [k dflt] (double-array (map #(double (get % k dflt)) wanted)))
        int' (fn [k dflt] (int-array (map #(long (get % k dflt)) wanted)))]
    {:idx      (int' :draw 1)
     :model    (int' :model 0)
     :base     (dbl :base 0.0)
     :spread   (int' :spread 1)
     :modulus  (int' :modulus 1)
     :base2    (dbl :base2 0.0)
     :spread2  (int' :spread2 1)
     :modulus2 (int' :modulus2 1)
     :jitter   (dbl :jitter 0.0)
     :minv     (double-array (map #(double (get minimums (:field %))) wanted))
     :n        (count wanted)
     :scale    (double (:scale plan))
     :fields   (mapv :field wanted)}))

(defn passes?
  "Whether `seed` meets every minimum. Bails at the first shortfall."
  [compiled ^long seed]
  (let [idx ^ints (:idx compiled), model ^ints (:model compiled)
        base ^doubles (:base compiled), spread ^ints (:spread compiled)
        modu ^ints (:modulus compiled), base2 ^doubles (:base2 compiled)
        spread2 ^ints (:spread2 compiled), modu2 ^ints (:modulus2 compiled)
        jit ^doubles (:jitter compiled), minv ^doubles (:minv compiled)
        n (long (:n compiled)), sp (double (:scale compiled))]
    (loop [i 0, s (first-draw seed), d 1]
      (if (>= i n)
        true
        (let [target (aget idx i)]
          (if (< d target)
            (recur i (step s) (inc d))
            (let [m (aget model i)
                  b (aget base i)
                  v (cond
                      (== m MODEL-CONV)
                      (converted s b (aget jit i))

                      (== m MODEL-PAIRMAX)
                      (trunc (+ (scaled-value (jittered s b (aget spread i) (aget modu i)) sp)
                                (jittered (step s) (aget base2 i) (aget spread2 i) (aget modu2 i))))

                      (== m MODEL-PAIRMAX-PLAIN)
                      (trunc (+ (jittered s b (aget spread i) (aget modu i))
                                (jittered (step s) (aget base2 i) (aget spread2 i) (aget modu2 i))))

                      (== m MODEL-SCALED)
                      (scaled-value (jittered s b (aget spread i) (aget modu i)) sp)

                      :else
                      (jittered s b (aget spread i) (aget modu i)))]
              (if (< v (aget minv i))
                false
                (recur (inc i) s d)))))))))

(defn search-range
  "Seeds in [from,to) satisfying the minimums, at most `want`.

  The arrays are pulled out of the compiled map once, before the loop. Reading
  them per seed instead costs eleven map lookups for every candidate examined,
  which turned out to dominate the arithmetic entirely.

  `halt` is checked occasionally rather than per seed -- often enough to stop
  promptly once another worker has the answer, rarely enough not to show up in
  the timings."
  ([compiled from to want]
   (search-range compiled from to want (atom false)))
  ([compiled from to want halt]
   (let [idx ^ints (:idx compiled), model ^ints (:model compiled)
         base ^doubles (:base compiled), spread ^ints (:spread compiled)
         modu ^ints (:modulus compiled), base2 ^doubles (:base2 compiled)
         spread2 ^ints (:spread2 compiled), modu2 ^ints (:modulus2 compiled)
         jit ^doubles (:jitter compiled), minv ^doubles (:minv compiled)
         n (long (:n compiled)), sp (double (:scale compiled))
         to (long to), want (long want)
         ok? (fn [^long seed]
               (loop [i 0, s (first-draw seed), d 1]
                 (if (>= i n)
                   true
                   (let [target (aget idx i)]
                     (if (< d target)
                       (recur i (step s) (inc d))
                       (let [m (aget model i)
                             b (aget base i)
                             v (cond
                                 (== m MODEL-PLAIN)
                                 (jittered s b (aget spread i) (aget modu i))

                                 (== m MODEL-SCALED)
                                 (scaled-value (jittered s b (aget spread i) (aget modu i)) sp)

                                 (== m MODEL-PAIRMAX)
                                 (trunc (+ (scaled-value (jittered s b (aget spread i) (aget modu i)) sp)
                                           (jittered (step s) (aget base2 i) (aget spread2 i) (aget modu2 i))))

                                 (== m MODEL-PAIRMAX-PLAIN)
                                 (trunc (+ (jittered s b (aget spread i) (aget modu i))
                                           (jittered (step s) (aget base2 i) (aget spread2 i) (aget modu2 i))))

                                 :else
                                 (converted s b (aget jit i)))]
                         (if (< v (aget minv i))
                           false
                           (recur (inc i) s d))))))))]
     (loop [seed (long from), hits [], found 0]
       (cond
         (or (>= seed to) (>= found want)) hits
         (and (zero? (bit-and seed 0xffff)) @halt) hits
         :else
         (if (ok? seed)
           (recur (inc seed) (conj hits seed) (inc found))
           (recur (inc seed) hits found)))))))

(def ^:const seed-space
  "Distinct random streams. Seeds are 32 bit, but the generator is modulo
  2^31-1, so a seed and that seed plus the modulus roll identically -- which is
  why the web finders report matches in pairs. Searching the lower half covers
  every roll that exists and returns the spelling that fits in an int."
  2147483647)

(defn search
  "Search the space in parallel, returning up to `want` seeds.

  The space is cut into many more chunks than there are workers, for two
  reasons. Cores are not equally fast -- the efficiency cores on an Apple
  machine take several times as long over the same chunk -- so a fixed slice
  each leaves most of them waiting on the slowest. And once enough matches are
  found the rest of the work is pointless: a rare combination whose matches all
  sit early in the space used to cost a full sweep of every other chunk.

  `progress`, if given, is an atom updated as chunks complete, so a caller can
  show something while a sweep of two billion seeds runs. It is deliberately
  updated per chunk rather than per seed: a shared counter touched a billion
  times would cost more than the search."
  ([compiled want] (search compiled want (.availableProcessors (Runtime/getRuntime))))
  ([compiled want workers] (search compiled want workers nil))
  ([compiled want workers progress]
   (let [chunks   512
         size     (quot seed-space chunks)
         next-ix  (atom 0)
         halt     (atom false)
         results  (atom [])
         take-one (fn [] (let [i (swap! next-ix inc)] (dec i)))
         run (fn []
               (loop []
                 (let [i (take-one)]
                   (when (and (< i chunks) (not @halt))
                     (let [from (inc (* (long i) size))
                           to   (if (= i (dec chunks)) seed-space (inc (* (inc (long i)) size)))
                           hits (search-range compiled from to want halt)]
                       (when (seq hits)
                         (let [total (swap! results into hits)]
                           (when (>= (count total) want)
                             (reset! halt true))))
                       (when progress
                         (swap! progress (fn [p]
                                           (-> p
                                               (update :scanned (fnil + 0) size)
                                               (assoc :found (count @results))))))
                       (recur))))))
         futs (doall (repeatedly workers #(future (run))))]
     (doseq [f futs] @f)
     (->> @results sort (take want) vec))))

(defn compile-best
  "Arrays for scoring every stat an item rolls, for a best-roll search.

  Unlike compile-search this keeps all the entries, not just constrained ones,
  and carries each stat's floor and span so a value can be scored as its
  position within its own range. Stats whose range has collapsed to a single
  value are dropped: they are the same for every seed and would only dilute the
  score."
  [plan]
  (let [wanted (->> (:entries plan)
                    (map (fn [e] (assoc e :range (stat-range plan e))))
                    (filter (fn [e] (let [[lo hi] (:range e)] (> (double hi) (double lo)))))
                    (sort-by :draw)
                    vec)
        dbl (fn [k dflt] (double-array (map #(double (get % k dflt)) wanted)))
        int' (fn [k dflt] (int-array (map #(long (get % k dflt)) wanted)))]
    {:idx      (int' :draw 1)
     :model    (int' :model 0)
     :base     (dbl :base 0.0)
     :spread   (int' :spread 1)
     :modulus  (int' :modulus 1)
     :base2    (dbl :base2 0.0)
     :spread2  (int' :spread2 1)
     :modulus2 (int' :modulus2 1)
     :jitter   (dbl :jitter 0.0)
     :lo       (double-array (map #(double (first (:range %))) wanted))
     :span     (double-array (map #(let [[lo hi] (:range %)]
                                     (- (double hi) (double lo))) wanted))
     :n        (count wanted)
     :scale    (double (:scale plan))
     :fields   (mapv :field wanted)}))

(defn aux-stream
  "Arrays for a bonus that rolls on a stream of its own.

  Pet bonuses and completion bonuses are both like this: a fresh generator
  primed with the item's seed, one draw per field in the engine's order. They
  are scored alongside the item's own stats because they come from the same
  seed, so a search that ignored them would leave them to chance -- and they are
  not correlated with the item's own roll, so chance does badly. Verified
  against the engine over 900 item/seed combinations."
  [bonus-record field-order ^double jitter]
  (let [fs (vec (filter #(let [v (get bonus-record %)]
                           (and (number? v) (pos? (double v))))
                        field-order))
        base (double-array (map #(double (get bonus-record %)) fs))
        conv (int-array (map #(if (re-find #"(?i)^conversionPercentage" %) 1 0) fs))
        spread (int-array (map #(spread-of (double (get bonus-record %)) jitter) fs))
        modulus (int-array (map #(inc (* 2 (spread-of (double (get bonus-record %)) jitter))) fs))
        lo (double-array (map (fn [f] (let [b (double (get bonus-record f))]
                                        (if (re-find #"(?i)^conversionPercentage" f)
                                          (max 0.0 (* b (- 1.0 (* jitter 0.01))))
                                          (- b (spread-of b jitter))))) fs))
        hi (double-array (map (fn [f] (let [b (double (get bonus-record f))]
                                        (if (re-find #"(?i)^conversionPercentage" f)
                                          (min 100.0 (* b (+ 1.0 (* jitter 0.01))))
                                          (+ b (spread-of b jitter))))) fs))]
    {:n (count fs) :fields fs :base base :conv conv :spread spread
     :modulus modulus :lo lo
     :span (double-array (map-indexed (fn [i _] (- (aget hi i) (aget lo i))) fs))
     :jitter jitter}))

(defn- best-in-range
  "The `want` highest-scoring seeds in [from,to), as [score seed] pairs.

  Every seed has to be scored -- there is no minimum to fail early against -- so
  this is the one search that always reads the whole range. A worker keeps only
  its own best few and they are merged at the end, which avoids any shared state
  in the loop."
  [compiled from to want halt]
  (let [idx ^ints (:idx compiled), model ^ints (:model compiled)
        base ^doubles (:base compiled), spread ^ints (:spread compiled)
        modu ^ints (:modulus compiled), base2 ^doubles (:base2 compiled)
        spread2 ^ints (:spread2 compiled), modu2 ^ints (:modulus2 compiled)
        jit ^doubles (:jitter compiled), lo ^doubles (:lo compiled)
        span ^doubles (:span compiled)
        n (long (:n compiled)), sp (double (:scale compiled))
        to (long to), want (long want)
        ;; Each stat contributes at most 1, so a seed whose score so far plus a
        ;; perfect remainder still cannot beat the worst candidate held is not
        ;; worth finishing. Once a near-perfect roll is in hand this dismisses
        ;; almost everything after a stat or two.
        auxes (vec (:aux compiled))
        aux-count (count auxes)
        aux-ns    (int-array (map #(long (:n %)) auxes))
        aux-base  (object-array (map :base auxes))
        aux-conv  (object-array (map :conv auxes))
        aux-spr   (object-array (map :spread auxes))
        aux-mod   (object-array (map :modulus auxes))
        aux-lo    (object-array (map :lo auxes))
        aux-span  (object-array (map :span auxes))
        aux-jit   (double-array (map #(double (:jitter %)) auxes))
        score-of (fn ^double [^long seed ^double floor]
                   (loop [i 0, s (first-draw seed), d 1, acc 0.0]
                     (if (>= i n)
                       acc
                       (if (<= (+ acc (- n i)) floor)
                         -1.0
                         (let [target (aget idx i)]
                         (if (< d target)
                           (recur i (step s) (inc d) acc)
                           (let [m (aget model i)
                                 b (aget base i)
                                 v (cond
                                     (== m MODEL-PLAIN)
                                     (jittered s b (aget spread i) (aget modu i))
                                     (== m MODEL-SCALED)
                                     (scaled-value (jittered s b (aget spread i) (aget modu i)) sp)
                                     (== m MODEL-PAIRMAX)
                                     (trunc (+ (scaled-value (jittered s b (aget spread i) (aget modu i)) sp)
                                               (jittered (step s) (aget base2 i) (aget spread2 i) (aget modu2 i))))
                                     (== m MODEL-PAIRMAX-PLAIN)
                                     (trunc (+ (jittered s b (aget spread i) (aget modu i))
                                               (jittered (step s) (aget base2 i) (aget spread2 i) (aget modu2 i))))
                                     :else
                                     (converted s b (aget jit i)))]
                             (recur (inc i) s d
                                    (+ acc (/ (- v (aget lo i)) (aget span i)))))))))))
        ;; Auxiliary streams cannot be pruned against -- each is a separate
        ;; generator, so nothing is known about it until it is walked. They are
        ;; few and short, so this is cheap next to the main stream.
        ;; Each auxiliary field contributes at most 1, so the best the aux
        ;; streams could possibly add is known before walking them. Subtracting
        ;; that from the bound keeps the main stream's pruning valid instead of
        ;; giving it up: without this every seed has to be scored in full, which
        ;; measured thirty-five times slower.
        aux-max (double (reduce + (map #(double (:n %)) auxes)))
        total-of (fn ^double [^long seed ^double floor]
                   (let [m (score-of seed (- floor aux-max))]
                     (if (neg? m)
                       -1.0
                       ;; A plain loop rather than reduce: the sequence machinery
                       ;; boxes, and this is per seed.
                       ;; Walked inline rather than in its own function:
                       ;; Clojure only allows primitive arguments up to four, and
                       ;; passing these as boxed values would undo the point of
                       ;; hoisting them.
                       (loop [j 0, acc m, remaining aux-max]
                         (if (>= j aux-count)
                           acc
                           (let [nj (long (aget aux-ns j))
                                 base ^doubles (aget ^objects aux-base j)
                                 conv ^ints (aget ^objects aux-conv j)
                                 spr ^ints (aget ^objects aux-spr j)
                                 mdl ^ints (aget ^objects aux-mod j)
                                 lo ^doubles (aget ^objects aux-lo j)
                                 span ^doubles (aget ^objects aux-span j)
                                 jit (aget aux-jit j)
                                 rest-max (- remaining (double nj))
                                 local-floor (- floor acc rest-max)
                                 got (loop [i 0, s (first-draw seed), a 0.0]
                                       (if (>= i nj)
                                         a
                                         (if (<= (+ a (- nj i)) local-floor)
                                           -1.0
                                           (let [b (aget base i)
                                                 v (if (== 1 (aget conv i))
                                                     (converted s b jit)
                                                     (jittered s b (aget spr i) (aget mdl i)))
                                                 sp (aget span i)]
                                             (recur (inc i) (step s)
                                                    (if (pos? sp)
                                                      (+ a (/ (- v (aget lo i)) sp))
                                                      a))))))]
                             (if (neg? got)
                               -1.0
                               (recur (inc j) (+ acc got) rest-max))))))))]
    (loop [seed (long from), best [], worst -1.0]
      (cond
        (>= seed to) best
        (and (zero? (bit-and seed 0xffff)) @halt) best
        :else
        (let [sc (total-of seed (if (< (count best) want) -1.0 worst))]
          (if (or (< (count best) want) (> sc worst))
            (let [b (->> (conj best [sc seed]) (sort-by first >) (take want) vec)]
              (recur (inc seed) b (first (peek b))))
            (recur (inc seed) best worst)))))))

(defn search-best
  "The `want` best-rolling seeds for an item, judged across every stat it rolls.

  This is the answer to \"just give me the best one\" -- no minimums, no guessing
  at what is achievable. It costs a full sweep every time, because a seed cannot
  be dismissed until it has been scored."
  ([compiled want] (search-best compiled want (.availableProcessors (Runtime/getRuntime)) nil))
  ([compiled want workers progress]
   ;; Warm the scoring loop before the real sweep. Cold, the JIT is still
   ;; interpreting it and the whole search runs several times slower -- measured
   ;; at 228 seconds cold against 40 warm on the same item. A couple of million
   ;; throwaway seeds is under a second and buys all of that back.
   (best-in-range compiled 1 2000000 1 (atom false))
   (let [chunks  512
         size    (quot seed-space chunks)
         next-ix (atom 0)
         halt    (atom false)
         results (atom [])
         run (fn []
               (loop []
                 (let [i (let [x (swap! next-ix inc)] (dec x))]
                   (when (< i chunks)
                     (let [from (inc (* (long i) size))
                           to   (if (= i (dec chunks)) seed-space (inc (* (inc (long i)) size)))]
                       (swap! results into (best-in-range compiled from to want halt))
                       (when progress
                         (swap! progress (fn [p] (update p :scanned (fnil + 0) size))))
                       (recur))))))
         futs (doall (repeatedly workers #(future (run))))]
     (doseq [f futs] @f)
     (->> @results (sort-by first >) (take want) (mapv second)))))

(def ^:private range-cache
  "Fitted ranges by record name.

  Fitting costs a couple of hundred engine calls, which is nothing once but adds
  up when a summary is printed for every item in a stash. Records are immutable
  for the life of a session, so the result can simply be kept."
  (atom {}))

(defn stat-ranges
  "field -> [low high] for `record`, or nil if the engine is unavailable.

  This is what lets an item summary show the roll next to the range it came
  from, the way the game's own tooltips do."
  [record]
  (when (and record (item-stats/available?))
    (if-let [hit (get @range-cache (:recordname record))]
      hit
      (let [rs (try (searchable-fields (fit-plan record))
                    (catch Throwable _ {}))]
        (swap! range-cache assoc (:recordname record) rs)
        rs))))
