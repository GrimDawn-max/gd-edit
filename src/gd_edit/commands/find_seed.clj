(ns gd-edit.commands.find-seed
  "Create an item that rolled the way you want it to.

  An item's numbers come from its seed, so this walks the seed space looking for
  one whose rolls clear the minimums you set, then builds the item with it. The
  search itself lives in gd-edit.seed-search; everything here is the
  conversation: which item did you mean, what do you want out of it, and where
  should it go."
  (:require [clojure.string :as str]
            [gd-edit.db-utils :as dbu]
            [gd-edit.globals :as globals]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.item-summary :as isum]
            [gd-edit.seed-search :as ss]
            [gd-edit.jline :as jl]
            [gd-edit.utils :as u]
            [gd-edit.commands.item :as item]
            [gd-edit.ascension :as ascension]
            [gd-edit.max-rolls :as max-rolls]
            [jansi-clj.core :refer [red green yellow]]))

;; ------------------------------------------------------------------- labelling

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
   "defensiveFreeze" "Reduced Freeze Duration"})

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

(defn- shown-value
  "A stat as the player sees it: whole numbers, matching the item summary.

  The engine computes conversions in single precision, so a maximum roll comes
  out as 18.000000715255737 rather than 18. Comparing or printing those raw
  makes a maximum roll look like it fell short of a maximum that is itself
  unprintable."
  ^long [v]
  (Math/round (double (or v 0.0))))

(defn- describe-field
  "What to call a stat on screen.

  The raw field name is the fallback, not the default: it is only worth showing
  when we have nothing better, and pairing it with a readable name everywhere
  just makes every line longer than it needs to be."
  [field]
  (or (field-label field) field))

;; ------------------------------------------------------------ item selection

(defn- item-record? [r]
  (and (string? (:recordname r))
       (str/starts-with? (:recordname r) "records/items/")
       (get r "itemClassification")
       (not (str/includes? (:recordname r) "/blueprints/"))
       (not (str/includes? (:recordname r) "/lootaffixes/"))))

(defn candidate-items
  "Item records whose displayed name contains `query`, best matches first.

  Substring rather than fuzzy on purpose. A player knows the name from the game
  or a build guide, and the thing they usually do not know is that the level 94
  version is called \"Mythical\" something -- which a substring search surfaces
  rather than silently choosing between."
  [query]
  (let [q (str/lower-case (str/trim query))]
    (->> (dbu/db)
         (filter item-record?)
         (keep (fn [r]
                 (when-let [n (dbu/item-base-record-get-name r)]
                   (when (str/includes? (str/lower-case n) q)
                     {:name n
                      :record r
                      :level (or (get r "levelRequirement") (get r "itemLevel") 0)
                      :class (get r "itemClassification")}))))
         ;; An expansion can restate a record the base game already defined, so
         ;; the same item turns up once per archive it appears in. The database
         ;; index resolves that by letting the last definition win; do the same
         ;; here rather than offering the player the same item twice.
         (reduce (fn [m c] (assoc m (:recordname (:record c)) c)) {})
         vals
         (sort-by (juxt :name :level))
         vec)))

(defn- prompt
  [text]
  (str/trim (or (jl/readline text) "")))

(defn- choose-item
  "Ask which of several same-named records was meant."
  [candidates]
  (cond
    (empty? candidates) nil
    (= 1 (count candidates)) (first candidates)
    :else
    (do
      (u/print-line)
      (doseq [[i c] (map-indexed vector candidates)]
        ;; 40 items have an "awakened" version -- the Ashes of Awakening upgrade
        ;; swaps the record for its counterpart under records/items/awakened/ --
        ;; and it keeps the same display name. Without a marker the list shows the
        ;; same name twice with no way to tell which is which.
        (u/print-line (format "  %2d. %-38s %-10s level %-4s%s"
                              (inc i) (:name c) (:class c) (:level c)
                              (if (str/includes? (str (:recordname (:record c))) "/items/awakened/")
                                "  (awakened)" ""))))
      (u/print-line)
      (let [answer (prompt (format "Which item? [1-%d, blank to cancel]: " (count candidates)))]
        (when-let [n (try (Integer/parseInt answer) (catch Exception _ nil))]
          (when (<= 1 n (count candidates))
            (nth candidates (dec n))))))))

;; ---------------------------------------------------------------- minimums

(defn- ask-minimums
  "Walk the searchable stats, collecting a minimum for the ones the user cares
  about. Blank leaves a stat unconstrained, which is what keeps a search from
  being impossible by accident."
  [plan]
  (let [entries (sort-by :draw (:entries plan))]
    (u/print-line)
    (u/print-line (format "%d rolling stats. Enter a minimum for the ones you want,"
                          (count entries)))
    (u/print-line "or press return to leave one unconstrained.")
    (u/print-line)
    (reduce
     (fn [acc e]
       (let [[lo hi] (ss/stat-range plan e)
             answer (prompt (format "  %-34s [%s-%s] : "
                                    (describe-field (:field e))
                                    (yellow (shown-value lo))
                                    (yellow (shown-value hi))))]
         (if (str/blank? answer)
           acc
           (if-let [v (try (Double/parseDouble answer) (catch Exception _ nil))]
             (if (> v (double (shown-value hi)))
               (do (u/print-line (red (format "     %s is above the maximum %d -- ignoring."
                                              (u/maybe-int v) (shown-value hi))))
                   acc)
               (assoc acc (:field e) v))
             (do (u/print-line (red "     not a number -- ignoring."))
                 acc)))))
     {}
     entries)))

;; ------------------------------------------------------------------ results

(def ^:private candidate-pool
  "How many matching seeds to gather before picking the best of them.

  Stopping at the first three means taking whatever the low end of the seed
  space happened to offer, which is rarely the best on offer. Collecting a pool
  first costs a little more scanning and produces markedly better items; for a
  filter tight enough that a thousand matches do not exist, the search reaches
  the end of the space anyway and the pool costs nothing."
  1000)

(defn- quality
  "How good a roll is, from 0 (every stat at its floor) to 1 (all at maximum).

  Scored on the stats the player constrained, since those are the ones they said
  they cared about, with everything else the item rolls as a tie-breaker. A stat
  whose range has collapsed to a single value tells us nothing and is skipped."
  [stats ranges fields]
  (let [score-of (fn [fs]
                   (let [scores (keep (fn [f]
                                        (let [[lo hi] (get ranges f)
                                              v (get stats f)]
                                          (when (and lo hi v (> (double hi) (double lo)))
                                            (/ (- (double v) (double lo))
                                               (- (double hi) (double lo))))))
                                      fs)]
                     (if (seq scores) (/ (reduce + scores) (count scores)) 0.0)))]
    [(score-of fields)
     (score-of (keys ranges))]))

(defn- bonus-quality
  "How good a seed's pet and completion bonuses are, from 0 to 1.

  These roll on streams of their own, primed from the same seed, and are
  uncorrelated with the item's own roll -- so among the seeds that clear the
  minimums they vary freely, and picking arbitrarily leaves them to chance.
  Option 1 already scores them inside the search; this is the equivalent for
  option 2, applied to the candidate pool rather than the whole seed space."
  [record extras seed]
  (let [item (merge {:basename (:recordname record) :seed seed
                     :prefix-name "" :suffix-name "" :modifier-name ""
                     :relic-name "" :relic-bonus "" :augment-name ""}
                    extras
                    {:seed seed})
        norm (fn [{:keys [values ranges]}]
               (let [ss (keep (fn [[f v]]
                                (let [[lo hi] (get ranges f)]
                                  (when (and lo hi v (> (double hi) (double lo)))
                                    (/ (- (double v) (double lo))
                                       (- (double hi) (double lo))))))
                              values)]
                 (when (seq ss) (/ (reduce + ss) (count ss)))))
        scores (keep identity
                     [(some-> (item-stats/pet-bonus record item) norm)
                      (some-> (item-stats/completion-bonus item) norm)])]
    (if (seq scores) (/ (reduce + scores) (count scores)) 0.0)))

(defn- with-progress
  "Run `f`, showing how far the search has got while it works.

  A sweep of two billion seeds can take several seconds, and a command that
  prints nothing for that long looks like it has hung. The line is rewritten in
  place and cleared afterwards, so the finished output reads as though it was
  never there."
  [f]
  (let [progress (atom {:scanned 0 :found 0})
        done (atom false)
        t0 (System/nanoTime)
        ;; A future inherits the root binding of *out*, not this thread's, and
        ;; `print` does not flush -- between them the progress line would be
        ;; written to the wrong stream and then sat in a buffer until the search
        ;; had already finished.
        out *out*
        reporter (future
                   (binding [*out* out]
                     (while (not @done)
                       (let [{:keys [scanned found]} @progress
                             pct (min 100.0 (* 100.0 (/ (double scanned) ss/seed-space)))
                             secs (/ (- (System/nanoTime) t0) 1e9)]
                         (u/print- (format "\r  %4.1fs  %,15d seeds  %5.1f%%  %d found        "
                                           secs (long scanned) pct found))
                         (flush))
                       (Thread/sleep 200))))]
    (try
      (f progress)
      (finally
        (reset! done true)
        @reporter
        ;; Wipe the progress line so it does not sit above the results.
        (u/print- (str "\r" (apply str (repeat 72 " ")) "\r"))
        (flush)))))

(defn- show-result
  "One candidate seed and everything it rolled, each value beside its range.

  Every stat is listed, not just the constrained ones. Candidates that tie on
  what you asked for still differ on what you did not, and those differences are
  the only thing that makes choosing between them meaningful -- showing only the
  stats you constrained made three identical-looking options out of three
  genuinely different items. A marker flags the ones you set a minimum on."
  [record idx seed asked-for all-fields ranges]
  (let [stats (item-stats/rolled-stats {:basename (:recordname record) :seed seed
                                        :prefix-name "" :suffix-name ""
                                        :modifier-name ""})
        asked (set asked-for)]
    (u/print-line (format "  %d. seed %s" idx (yellow seed)))
    (doseq [f all-fields]
      (let [v (shown-value (get stats f))
            [lo hi] (get ranges f)
            at-max? (and hi (>= v (shown-value hi)))]
        (u/print-line (format "     %s %-32s %s %s"
                              (if (asked f) ">" " ")
                              (describe-field f)
                              ((if at-max? green yellow) v)
                              (if lo
                                (format "[%d-%d]" (shown-value lo) (shown-value hi))
                                "")))))))

(defn- place!
  "Build the chosen item and put `n` copies of it somewhere.

  Every copy carries the same seed, so they are the same item rather than n
  fresh rolls -- which is the whole point, and the opposite of what `batch item`
  does. How many actually fit is decided by the container: copies are placed one
  at a time until the requested number is reached or there is no room left, and
  the count that landed is reported either way."
  [record seed path-str n extras]
  (let [item (merge (assoc (item/blank-item) :basename (:recordname record) :seed seed)
                    extras)]
    (if-let [result (item/data-at-fuzzy-path @globals/character path-str)]
      (let [{:keys [actual-path]} result
            placed (loop [i 0, last-path nil]
                     (if (>= i (long n))
                       {:count i :last last-path}
                       (if-let [p (item/place-item-in-inventory! globals/character actual-path item)]
                         (recur (inc i) p)
                         {:count i :last last-path})))
            c (:count placed)]
        (cond
          (zero? c)
          (u/print-line (red "Sorry, there is no room to fit the item."))

          :else
          (do
            (u/print-line)
            (if (= 1 c)
              (u/print-line "Item placed in" (yellow (str (:last placed))))
              (u/print-line (format "%d copies placed, the last in %s"
                                    c (yellow (str (:last placed))))))
            (when (< c (long n))
              (u/print-line (yellow (format "Only %d of %d fit -- the container filled up." c n))))
            (u/print-line)
            (doseq [l (isum/item-summary item)] (u/print-line l))
            (u/print-line)
            (u/print-line (yellow "Remember to `write` before loading the game.")))))
      (u/print-line (red (format "Cannot find \"%s\" to place the item into." path-str))))))

;; ------------------------------------------------------------------ handler

(defn- stat-label
  "A bonus record described by what it gives, since these have no useful name."
  [rec]
  (->> rec
       (filter (fn [[k v]] (and (string? k) (number? v)
                                (not (#{"lootRandomizerJitter" "levelRequirement"
                                        "lootRandomizerCost" "marketAdjustmentPercent"} k)))))
       (map (fn [[k v]] (format "%s %s" (or (field-label k) k) (u/maybe-int v))))
       (str/join ", ")))

(defn- choose-from
  "Offer `candidates` -- [label record] pairs -- and return the chosen record.

  Typing filters rather than paging: several of these lists run to hundreds of
  entries, and nobody wants to scroll a numbered list of 384 augments."
  [what candidates]
  (when (seq candidates)
    (u/print-line)
    (let [q (prompt (format "%s? Type part of a name to search, or return to skip: " what))]
      (when-not (str/blank? q)
        (let [ql (str/lower-case q)
              hits (->> candidates
                        (filter (fn [[label _]] (str/includes? (str/lower-case label) ql)))
                        (take 20)
                        vec)]
          (cond
            (empty? hits)
            (do (u/print-line (red "  nothing matches that.")) nil)

            :else
            (do
              (doseq [[i [label _]] (map-indexed vector hits)]
                (u/print-line (format "  %2d. %s" (inc i) label)))
              (let [pick (prompt (format "Which? [1-%d, blank to skip]: " (count hits)))]
                (when-let [n (try (Integer/parseInt pick) (catch Exception _ nil))]
                  (when (<= 1 n (count hits))
                    (second (nth hits (dec n)))))))))))))

(defn- rolls? [rec]
  (let [j (get rec "lootRandomizerJitter")]
    (and j (number? j) (pos? (double j)))))

(defn- records-under [prefix]
  (->> (dbu/db)
       (filter #(and (string? (:recordname %))
                     (str/starts-with? (:recordname %) prefix)))))

(defn- named-candidates [prefix]
  (->> (records-under prefix)
       (keep (fn [r] (when-let [n (dbu/item-base-record-get-name r)] [n r])))
       (sort-by first)
       vec))

(defn- skill-label
  "The player-facing name of a skill record."
  [path]
  (when-let [r (dbu/record-by-name path)]
    (let [tag (dbu/skill-display-name r)]
      (or (get (dbu/localization-table) tag)
          tag
          (last (str/split (str path) #"/"))))))

(defn- ascended-label
  "Describe an ascended affix by what it grants.

  The mastery affixes give +N to a skill plus a modifier, so stat-label's raw
  field dump reads as gibberish for them -- what matters is the skill name."
  [rec]
  (let [sk (get rec "augmentSkillName1")
        lv (get rec "augmentSkillLevel1")
        modified (get rec "modifiedSkillName1")]
    (cond
      (and sk lv)
      (let [granted (or (skill-label sk) "?")
            m (some-> modified skill-label)]
        ;; The modified skill is usually the same one being augmented, in which
        ;; case naming it twice just adds noise.
        (format "+%s to %s%s" (u/maybe-int lv) granted
                (if (and m (not= m granted)) (format "   (modifies %s)" m) "")))

      modified
      (format "modifier to %s" (or (skill-label modified) "?"))

      :else
      ;; The generic (non-mastery) affixes are plain stat bonuses, but they carry
      ;; augmentSkillLevel entries with no companion skill name -- vestigial, and
      ;; pure noise in a label. Drop them and describe the stats.
      (stat-label (into {} (remove (fn [[k _]]
                                     (and (string? k)
                                          (re-find #"^augmentSkillLevel\d+$" k)))
                                   rec))))))

(defn- ascended-candidates
  "The ascended affixes this item could legally receive.

  Drawn from the game's own blueprint tables for the item's rarity and category,
  so the list only offers affixes the Kurnhold altar could actually produce. They
  are fixed values with no jitter, so nothing here affects the search."
  [record]
  (->> (ascension/legal-affixes {:basename (:recordname record)})
       (keep dbu/record-by-name)
       (map (fn [r] [(ascended-label r) r]))
       (sort-by first)
       vec))

(defn- blacksmith-candidates []
  (->> (records-under "records/items/lootaffixes/crafting/")
       (filter rolls?)
       (map (fn [r] [(stat-label r) r]))
       (sort-by first)
       vec))

(defn- completion-candidates
  "The completion bonuses this particular item can roll.

  Taken from the item's own bonus table rather than the whole family, so the
  choice is limited to what the game would actually give it."
  [record]
  (when-let [table (some-> (get record "bonusTableName") not-empty dbu/record-by-name)]
    (->> table
         (filter (fn [[k _]] (and (string? k) (str/starts-with? k "randomizerName"))))
         (keep (fn [[_ v]] (dbu/record-by-name v)))
         (map (fn [r] [(stat-label r) r]))
         (sort-by first)
         vec)))

(defn- offer-and-place
  "Show the candidates, then build and place whichever one is chosen."
  [record hits asked-for all-fields ranges extras]
  (if (empty? hits)
    (u/print-line (red "No seed produces those stats.")
                  "An item with this combination does not exist.")
    (do
      (u/print-line (if (seq asked-for)
                      (format "  (%s marks a stat you set a minimum on; %s is at maximum)"
                              ">" (green "green"))
                      (format "  (%s is at the top of its range)" (green "green"))))
      (u/print-line)
      (doseq [[i s] (map-indexed vector hits)]
        (show-result record (inc i) s asked-for all-fields ranges))
      (u/print-line)
      (let [pick (prompt (format "Create which one? [1-%d, blank to cancel]: " (count hits)))]
        (when-let [n (try (Integer/parseInt pick) (catch Exception _ nil))]
          (when (<= 1 n (count hits))
            ;; Components and augments contribute fixed values and do not
            ;; consume draws, so they cannot affect the search and are chosen
            ;; afterwards -- the item comes out finished rather than needing
            ;; fields set by hand.
            (let [component (choose-from "Add a component" (named-candidates "records/items/materia/"))
                  augment   (choose-from "Add an augment" (named-candidates "records/items/enchants/"))
                  ascended  (choose-from "Add an ascended bonus" (ascended-candidates record))
                  where (prompt "Place where? [inv/0/items]: ")
                  where (if (str/blank? where) "inv/0/items" where)
                  hm (prompt "How many copies? [1]: ")
                  copies (or (when-not (str/blank? hm)
                               (try (max 1 (Integer/parseInt hm)) (catch Exception _ nil)))
                             1)]
              (place! record (nth hits (dec n)) where copies
                      (cond-> extras
                        ;; A component needs a seed of its own: 84 of the 109
                        ;; carry a completion bonus drawn from a table, and the
                        ;; game picks it from relic-seed rather than storing it
                        ;; (no real save has relic-bonus set on a component).
                        ;; Leaving the seed at 0 is a state no legitimately
                        ;; obtained item is ever in.
                        component (assoc :relic-name (:recordname component)
                                         :relic-seed (rand-int Integer/MAX_VALUE)
                                         :relic-completion-level 4)
                        ;; Augments do not roll, so the seed changes no stats --
                        ;; but every augmented item in a real save carries a
                        ;; non-zero one, and make-char already assigns them.
                        augment   (assoc :augment-name (:recordname augment)
                                         :augment-seed (rand-int Integer/MAX_VALUE))
                        ascended  (assoc :ascended-name (:recordname ascended)))))))))))

(defn- run-with-minimums
  "Search for rolls that clear the minimums the player sets."
  [record plan extras]
  (let [minimums (ask-minimums plan)]
    (if (empty? minimums)
      (u/print-line "No minimums given, so there is nothing to search for.")
      (let [compiled (ss/compile-search plan minimums)
            ranges   (ss/searchable-fields plan)
            ;; Draw order, which is the order the minimums were asked for -- a
            ;; result listed in a different order to the questions is needlessly
            ;; hard to check.
            all-fields (->> (:entries plan) (sort-by :draw) (mapv :field))
            asked    (filterv #(contains? minimums %) all-fields)
            stats-of (fn [seed]
                       (item-stats/rolled-stats
                        {:basename (:recordname record) :seed seed
                         :prefix-name "" :suffix-name "" :modifier-name ""}))]
        (u/print-line)
        (u/print-line (format "Searching %,d seeds..." ss/seed-space))
        (let [t0 (System/nanoTime)
              pool (with-progress
                     (fn [progress]
                       (ss/search compiled candidate-pool
                                  (.availableProcessors (Runtime/getRuntime))
                                  progress)))
              secs (/ (- (System/nanoTime) t0) 1e9)
              ;; Rank on what was asked for first, then everything else the item
              ;; rolls, then the pet and completion bonuses. Seeds that tie on the
              ;; minimums still differ on those, and that difference is usually
              ;; the only thing separating one candidate from another.
              hits (->> pool
                        (sort-by (fn [seed]
                                   (conj (quality (stats-of seed) ranges asked)
                                         (bonus-quality record extras seed)))
                                 #(compare %2 %1))
                        (take 3)
                        vec)]
          (u/print-line (format "  %s, %d matching seeds found%s"
                                (if (< secs 1.0)
                                  (format "%.0f ms" (* secs 1000))
                                  (format "%.1f seconds" secs))
                                (count pool)
                                (if (>= (count pool) candidate-pool)
                                  ", showing the best of them" "")))
          (u/print-line)
          (offer-and-place record hits asked all-fields ranges extras))))))

(defn- run-for-maximums
  "Find the roll that gets each stat as high as it will go.

  Nothing to enter and nothing to guess: every seed is scored on how close it
  comes to the top of every stat's range, and the highest scoring are offered.
  Because it maximises rather than filters, there is always an answer -- an item
  with every stat at its ceiling may not exist, but the closest one to it always
  does."
  [record plan extras completion-aux]
  (let [;; A pet bonus rolls from the same seed on a stream of its own, and is
        ;; uncorrelated with the item's own roll -- so a search that ignored it
        ;; would leave it to chance, and chance does badly. On Mogdrogen's Ardor
        ;; the best-for-base seed scores 0.56 on pets; scoring both together
        ;; finds seeds that max everything.
        ;;
        ;; Shared with make-char's --max-rolls rather than written twice: the two
        ;; must agree on what "best" means, and a second copy would drift.
        pet-aux (max-rolls/pet-aux record)
        auxes (vec (remove nil? [pet-aux completion-aux]))
        compiled (cond-> (ss/compile-best plan)
                   (seq auxes) (assoc :aux auxes))
        ranges   (ss/searchable-fields plan)
        fields   (:fields compiled)]
    (u/print-line)
    (u/print-line (format "Finding the highest reachable value across %d stats%s,"
                          (:n compiled)
                          (if pet-aux
                            (format " and %d pet bonus stats" (:n pet-aux))
                            "")))
    (u/print-line (format "over all %,d seeds..." ss/seed-space))
    (let [t0 (System/nanoTime)
          hits (with-progress
                 (fn [progress]
                   (ss/search-best compiled 3
                                   (.availableProcessors (Runtime/getRuntime))
                                   progress)))
          secs (/ (- (System/nanoTime) t0) 1e9)
          maxed (fn [seed]
                  (let [st (item-stats/rolled-stats
                            {:basename (:recordname record) :seed seed
                             :prefix-name "" :suffix-name "" :modifier-name ""})]
                    (count (filter (fn [f]
                                     (let [[_ hi] (get ranges f) v (get st f)]
                                       (and hi v (>= (shown-value v) (shown-value hi)))))
                                   fields))))]
      (u/print-line (format "  %.1f seconds -- best reachable has %d of %d stats at maximum"
                            secs (maxed (first hits)) (:n compiled)))
      (u/print-line)
      ;; Nothing was asked for, so nothing is marked as asked for.
      (offer-and-place record hits [] fields ranges extras)
      ;; Pet bonuses were part of what was maximised, so show what was achieved.
      (when pet-aux
        (doseq [[i seed] (map-indexed vector hits)]
          (let [pb (item-stats/pet-bonus record {:basename (:recordname record) :seed seed})]
            (u/print-line)
            (u/print-line (format "  %d. seed %s -- Bonus to All Pets" (inc i) (yellow seed)))
            (doseq [[f v] (:values pb)]
              (let [[lo hi] (get (:ranges pb) f)
                    at-max? (and hi (>= (shown-value v) (shown-value hi)))]
                (u/print-line (format "       %-32s %s %s"
                                      (describe-field f)
                                      ((if at-max? green yellow) (shown-value v))
                                      (if lo (format "[%d-%d]" (shown-value lo) (shown-value hi)) "")))))))))))

(defn find-seed-handler
  [[_ tokens]]
  ;; The whole rest of the line is the name. Reading only the first token made
  ;; "find-seed Mythical Amatok's Step" search for "Mythical" and quietly
  ;; discard the rest, which matches every Mythical item in the game.
  (let [query (some-> (seq tokens) (->> (str/join " ")) str/trim not-empty)]
    (cond
      (nil? query)
      (u/print-line "Syntax: find-seed <item name>")

      (not (item-stats/available?))
      (u/print-line (red "The item stat engine is not available in this build,")
                    "so seeds cannot be searched.")

      :else
      (if-let [choice (choose-item (candidate-items query))]
        (let [record (:record choice)]
          (u/print-line)
          (u/print-line "Analysing" (yellow (:name choice)) "...")
          ;; Asked before the search, because both change what is being
          ;; searched for. A blacksmith bonus rolls on the item's own stream and
          ;; shifts every stat after it, so the plan has to be fitted with it in
          ;; place; a completion bonus rolls on a stream of its own and is
          ;; scored alongside everything else.
          (let [blacksmith (choose-from "Add a blacksmith bonus" (blacksmith-candidates))
                completion (choose-from "Add a completion bonus" (completion-candidates record))
                plan (ss/fit-plan record (:recordname blacksmith))
                completion-aux
                (when completion
                  (let [jit (double (or (get completion "lootRandomizerJitter") 0.0))
                        order (keys (item-stats/rolled-stats
                                     {:basename (:recordname completion) :seed 1
                                      :prefix-name "" :suffix-name "" :modifier-name ""}))]
                    (ss/aux-stream completion order jit)))
                extras (cond-> {}
                         blacksmith (assoc :modifier-name (:recordname blacksmith))
                         completion (assoc :relic-bonus (:recordname completion)))]
            (cond
              (empty? (:entries plan))
              (u/print-line (red "This item has no rolling stats to search on."))

              (not (ss/plan-valid? plan))
              (u/print-line (red "Could not work out how this item rolls, so searching it")
                            (red "would only produce plausible-looking nonsense. Aborted."))

              :else
              (do
                (when (seq (:unfittable plan))
                  (u/print-line (yellow "Note:") "these stats cannot be searched on:"
                                (str/join ", " (:unfittable plan))))
                (u/print-line)
                (u/print-line (format "%s rolls %d stats."
                                      (:name choice) (count (:entries plan))))
                (u/print-line "  1) Get each stat as high as it will go -- nothing to enter")
                (u/print-line "  2) Set your own minimums")
                (u/print-line)
                (if (= "2" (prompt "Which? [1]: "))
                  (run-with-minimums record plan extras)
                  (run-for-maximums record plan extras completion-aux))))))
        (u/print-line (red "Sorry,") (format "no item found matching \"%s\"" query))))))
