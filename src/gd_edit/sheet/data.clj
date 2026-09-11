(ns gd-edit.sheet.data
  "Everything the character sheet displays, gathered from the save and the
  database.

  This is the work the sheet was prototyped with as four separate scripts --
  one for the character and its equipment, one for the mastery trees, one for
  the devotions, one for the buffs -- brought together so the database is read
  once rather than four times. Each returns plain data; turning it into a page
  is `gd-edit.sheet.render`'s job.

  Nothing here loads the database: a running gd-edit already has it."
  (:require [clojure.string :as str]
            [gd-edit.db-utils :as dbu]
            [gd-edit.item-stats :as is]
            [gd-edit.seed-search :as ss]
            [gd-edit.labels :as labels]
            [gd-edit.ascension :as asc]
            [gd-edit.character-stats :as cs]
            [gd-edit.resistances :as res]
            [gd-edit.item-summary :as isum]
            [gd-edit.game-dirs :as dirs]))

(def ^:private toggled
  "Buff classes that stay on until switched off, and reserve energy."
  #{"Skill_BuffSelfToggled" "Skill_BuffRadiusToggled" "Skill_BuffAttackRadiusToggled"})

(def ^:private cast-buff
  "Buff classes that run for a while once cast or triggered."
  #{"Skill_BuffSelfDuration" "Skill_BuffRadius" "Skill_BuffOther"})

(defn- trigger-text
  "What sets a proc off, in the game's words rather than the record's."
  [c]
  (when c
    (let [pct (some-> (get c "chanceToRun") long)
          t (str (get c "triggerType"))]
      (str pct "% Chance "
           (case t
             "AttackEnemy" "on Attack"
             "HitByEnemy" "when Hit"
             "LowHealth" "at Low Health"
             "Kill" "on Kill"
             (str "(" t ")"))))))

(defn character-data
  "The character, its computed stats, and every equipped item with what it rolled."
  [character]
  (let [slots [[0 "Head"] [1 "Amulet"] [2 "Chest"] [3 "Legs"] [4 "Feet"] [5 "Hands"]
               [6 "Ring"] [7 "Ring"] [8 "Belt"] [9 "Shoulders"] [10 "Medal"] [11 "Relic"]]
        c character
        rnd (fn [v] (Math/round (double v)))
        ;; The phrasing is gd-edit's own, from item-summary. Going through
        ;; field-label instead -- which is what this used to do -- loses the
        ;; sentences the game actually writes ("Increases Health Regeneration by
        ;; 42%" arrives as a bare label with the value torn off into a column),
        ;; prints raw field names for anything the label table misses, and shows
        ;; a weapon's base damage as if it were a bonus. item-summary already
        ;; knows all of that, so the only thing rebuilt here is the grouping,
        ;; because item-summary flattens its groups into one list of strings and
        ;; the page needs them apart.
        worn-bases (into #{} (comp (map :basename) (map str) (remove str/blank?))
                         (concat (:equipment c) (get-in c [:weapon-sets 0 :items])))
        calc-isl (requiring-resolve 'gd-edit.item-summary/calc-item-skill-level)
        set-tiers (requiring-resolve 'gd-edit.item-summary/set-bonus-tiers)
        lines (fn [xs] (->> xs flatten (remove nil?) (map str)
                            (map #(str/replace % #"\s+$" "")) vec))
        ;; A summary lists a record's own stats first, then named sub-blocks.
        ;; Three records feed one item -- the base and its two affixes -- and
        ;; simply concatenating their summaries drops the prefix's own stats
        ;; immediately after the base record's "Bonus to All Pets", where they
        ;; read as part of it. The game merges the three into one list with a
        ;; single block of each kind, so the blocks are collected by name across
        ;; all three and emitted once.
        esc (str (char 27))
        ansi-re (re-pattern (str esc "\\[[0-9;]*m"))
        plain (fn [x] (str/replace (str x) ansi-re ""))
        top-heads ["Bonus to All Pets" "Granted Skills"]
        sections
        (fn [ls]
          (:sec (reduce
                 (fn [acc l]
                   (let [t (plain l), k (or (:cur acc) :main)]
                     (cond
                       ;; only an unindented heading splits the top level; an
                       ;; indented one belongs to the block it sits inside
                       (and (= t (str/trim t)) (some #{(str/trim t)} top-heads))
                       (assoc acc :cur (str/trim t))
                       ;; a blank between blocks is noise; one inside a block is
                       ;; the summary's own paragraph break and is kept
                       (str/blank? t)
                       (cond-> acc (seq (get-in acc [:sec k])) (update-in [:sec k] conj l))
                       :else (update-in acc [:sec k] (fnil conj []) l))))
                 {:cur nil :sec {}} ls)))
        merge-summaries
        (fn [& summaries]
          (let [secs (map (comp sections lines vector) summaries)
                trim-tail (fn [v] (vec (reverse (drop-while #(str/blank? (plain %)) (reverse v)))))
                ;; each record's own trailing blank is trimmed before the three
                ;; are joined, so the stats read as the single list the game
                ;; prints rather than three paragraphs
                part (fn [k] (vec (mapcat #(trim-tail (get % k)) secs)))]
            (concat (trim-tail (part :main))
                    (mapcat (fn [h]
                              (when-let [xs (seq (trim-tail (part h)))]
                                (concat ["" h] xs)))
                            top-heads))))
        ;; a group with nothing in it is a heading the game would not draw
        group (fn [kind heading xs & [extra]]
                (let [ls (lines xs)]
                  (when (seq (remove str/blank? ls))
                    (merge {:kind kind :heading heading :lines ls} extra))))
        ;; the line the game prints under an attachment's name -- "Magical
        ;; Component", "Rare Augment". record-class-display-name has no entry
        ;; for either class, so the two are named here.
        attach-kind (fn [r]
                      (let [cls (str (get r "Class"))
                            nm (case cls
                                 "ItemRelic" "Component"
                                 "ItemEnchantment" "Augment"
                                 (isum/record-class-display-name cls))]
                        (str/trim (str (some-> (get r "itemClassification") not-empty (str " ")) nm))))
        item->map
        (fn [it slot]
          (when (not-empty (str (:basename it)))
            (let [rec (dbu/record-by-name (:basename it))
                  plan (ss/fit-plan rec (some-> (:modifier-name it) not-empty)
                                    (some-> (:prefix-name it) not-empty)
                                    (some-> (:suffix-name it) not-empty))
                  ranges (when plan (ss/searchable-fields plan))
                  st (is/rolled-stats it)
                  named (fn [p] (some-> p not-empty dbu/record-by-name dbu/item-base-record-get-name))
                  base (is/with-rolled-stats rec it)
                  isl (calc-isl base)
                  at-level (fn [r] (cond-> r isl (assoc "itemSkillLevel" isl)))
                  ;; ranges are fitted from the record's *base* values, so the
                  ;; unrolled record has to be the one measured
                  own-ranges (merge (ss/stat-ranges rec) (is/modifier-ranges it))
                  groups
                  (binding [isum/*stat-ranges* own-ranges
                            isum/*pet-bonus* (is/pet-bonus rec it)]
                    (doall
                     (remove
                      nil?
                      [;; the item's own stats, with its affixes, the way the
                       ;; game runs them together above the horizontal rule
                       (group "item" nil
                              [(merge-summaries
                                (isum/effect-summary
                                 (->> (dissoc base "defensiveProtection" "offensivePhysicalMin"
                                              "offensivePierceRatioMin")
                                      (remove (fn [[k _]] (str/starts-with? k "offensiveBase")))
                                      (into {})))
                                (some-> (dbu/record-by-name (:prefix-name it)) at-level isum/effect-summary)
                                (some-> (dbu/record-by-name (:suffix-name it)) at-level isum/effect-summary))])
                       (when-let [r (dbu/record-by-name (:relic-name it))]
                         (group "component" (or (dbu/item-base-record-get-name r) "Component")
                                [(isum/effect-summary r #{:component})]
                                {:sub (attach-kind r)}))
                       (when-let [r (dbu/record-by-name (:relic-bonus it))]
                         (let [cb (is/completion-bonus it)]
                           (group "bonus" "Completion Bonus"
                                  [(binding [isum/*stat-ranges* (:ranges cb)]
                                     (doall (isum/effect-summary (merge r (:values cb)) #{})))])))
                       (when-let [r (dbu/record-by-name (:augment-name it))]
                         (group "augment" (or (dbu/item-base-record-get-name r) "Augment")
                                [(isum/effect-summary r #{:augment})]
                                ;; an augment is coloured by its own rarity, the
                                ;; way the game colours it; a component is always
                                ;; gold whatever its rarity
                                {:sub (attach-kind r)
                                 :rarity (some-> (get r "itemClassification") not-empty str)}))
                       (when-let [r (dbu/record-by-name (:ascended-name it))]
                         (group "ascended" "Ascended Bonus"
                                [(isum/effect-summary r #{:ascended})]))
                       (when-let [sr (some-> (get rec "itemSetName") not-empty dbu/record-by-name)]
                         (let [members (let [m (get sr "setMembers")]
                                         (if (sequential? m) m (when m [m])))]
                           (assoc
                            (group "set" (str (or (get sr "setName") "Set")
                                              (format " (%d pieces)" (count members)))
                                   (for [[n fields] (set-tiers sr members)]
                                     [(format "%d pieces:" n)
                                      ;; indent the lines, not the elements: a
                                      ;; summary nests, so prefixing its
                                      ;; top-level items leaves anything inside
                                      ;; a nested sequence hanging at the margin
                                      (isum/indent-all (isum/effect-summary fields #{:set}))]))
                            ;; the game highlights the pieces actually being
                            ;; worn, which is what says whether the next tier is
                            ;; within reach; `this` marks the hovered piece
                            :members (vec (for [m members]
                                            {:name (or (dbu/item-base-record-get-name
                                                        (dbu/record-by-name m)) (str m))
                                             :worn (contains? worn-bases (str m))
                                             :this (= m (:basename it))})))))])))]
              {:slot slot
               :name (str (dbu/item-name it (dbu/db-and-index)))
               :rarity (str (get rec "itemClassification"))
               :seed (:seed it)
               :component (named (:relic-name it))
               :augment (named (:augment-name it))
               :ascended (some-> (:ascended-name it) not-empty dbu/record-by-name asc/affix-label)
               ;; the class line and the weapon-damage / armour block the game
               ;; prints above everything else
               :kind (str/join " " (conj (vec (vals (select-keys base ["itemClassification"
                                                                       "armorClassification"])))
                                         (isum/record-class-display-name (get base "Class"))))
               :primary (lines [(isum/record-primary-attributes base)])
               :groups (vec groups)
               :requires (lines [(when-let [l (get base "levelRequirement")]
                                   (format "Required Player Level: %s" (isum/number l)))
                                 (isum/record-cost base)])
               :itemLevel (some-> (get base "itemLevel") long)
               ;; kept for the max-roll colouring on the paper doll
               :stats (vec (for [[f v] (sort-by key st)
                                 :let [[lo hi] (get ranges f)]]
                             {:label (or (labels/field-label f) f)
                              :value (rnd v)
                              :lo (when lo (rnd lo)) :hi (when hi (rnd hi))
                              :max (boolean (and hi (>= (rnd v) (rnd hi))))
                              :searchable (boolean (get ranges f))}))})))
        equip (vec (keep (fn [[i nm]] (item->map (get-in c [:equipment i]) nm)) slots))
        weapons (vec (keep-indexed (fn [i it] (item->map it (if (zero? i) "Weapon" "Off-hand")))
                                   (get-in c [:weapon-sets 0 :items])))
        masteries (->> (:skills c)
                       (keep :skill-name)
                       (filter #(= "Skill_Mastery" (get (dbu/record-by-name %) "Class")))
                       (keep #(asc/mastery-display-name (second (re-find #"/(playerclass\d+)/" (str %)))))
                       vec)]
    (identity
              {:character {:name (:character-name c) :level (:character-level c)
                           :masteries masteries
                           :difficulty (get ["Normal" "Elite" "Ultimate"] (res/difficulty c))
                           ;; the newest expansion present, by its own folder
                           :expansion (cond
                                        (.exists (dirs/get-gdx3-dir)) "Fangs of Asterkarn"
                                        (.exists (dirs/get-gdx2-dir)) "Forgotten Gods"
                                        (.exists (dirs/get-gdx1-dir)) "Ashes of Malmouth"
                                        :else "Grim Dawn")
                           ;; The game stamps its own version at the top of its
                           ;; log every time it starts. Nothing in the install
                           ;; carries it -- the executable's PE version reads
                           ;; 0.3.0.0, a placeholder -- so the log is the source.
                           :version (let [f (java.io.File.
                                             (str (System/getProperty "user.home")
                                                  "/Documents/My Games/Grim Dawn/log.html"))]
                                      (when (.exists f)
                                        (with-open [r (clojure.java.io/reader f)]
                                          (some #(second (re-find #"Version:\s*(v[0-9.]+)" %))
                                                (take 40 (line-seq r))))))}
               ;; computed, not read off the save -- see gd-edit.character-stats.
               ;; The save's own :physique and friends are the base before gear,
               ;; masteries and devotions, which on this character is 754 against
               ;; a real 1154, and 2262 health against 22783.
               :stats (let [st (cs/compute c)]
                        {:physique (:physique st) :cunning (:cunning st) :spirit (:spirit st)
                         :health (:health st)
                         :energy (:energy st) :energyUsable (:energy-usable st)
                         :energyReserved (:energy-reserved st)
                         :offensiveAbility (:offensive-ability st)
                         :defensiveAbility (:defensive-ability st)
                         :armour (:armour st) :armourBySlot (:armour-by-slot st)})
               ;; over = how far past the cap the total runs. The game reports it
               ;; on hover; without it a capped figure hides any amount of error.
               :resistances (vec (for [r (res/compute c)
                                       :let [before (+ (:total r) (:penalty r))]]
                                   {:field (:field r) :label (:label r)
                                    :shown (:shown r)
                                    :cap (long (Math/round (:cap r)))
                                    :over (max 0 (long (Math/round (- before (:cap r)))))}))
               ;; What the hover panels need: the game's own description of each
               ;; stat, the base/gear split the game prints beside it, and the
               ;; derived figures its tooltips report.
               :statInfo
               (let [st (cs/compute c)
                     tot (res/collect (mapcat val (res/stat-sources c))
                                      ["defensiveAbsorptionModifier"])
                     loc (dbu/localization-table)
                     txt (fn [tag] (some-> (get loc tag) str (str/replace #"\{\^[A-Za-z]\}" "")
                                           (str/replace #"\^[a-z]" " ") str/trim))
                     ex (:exact st)
                     ;; the game's tooltips quote these from the untruncated
                     ;; attribute, not the figure it prints
                     bonus-health (fn [k per] (Math/round (* per (- (get ex k) 50.0))))
                     saved {:physique (:physique c) :cunning (:cunning c) :spirit (:spirit c)}
                     ;; the save holds these as doubles; the game prints whole numbers
                     split (fn [k shown]
                             (let [b (rnd (get saved k))] [b (- shown b)]))
                     ;; base health and energy are what the attributes alone give;
                     ;; the rest is gear, the way the game shows it in blue
                     base-health (Math/round (+ 250.0 (* 2.5 (- (:physique ex) 50.0))
                                                (* 1.0 (- (:cunning ex) 50.0))
                                                (* 1.5 (- (:spirit ex) 50.0))))
                     base-energy (Math/round (+ 250.0 (* 2.0 (- (:spirit ex) 50.0))))]
                 {:physique {:desc (txt "tagCharAttributeDescription02")
                             :bonusHealth (bonus-health :physique 2.5)
                             ;; 0.04 health regen a point above the starting 50:
                             ;; 1104 points over gives the 44.16 the game reports
                             :bonusRegen (format "%.2f" (* 0.04 (- (:physique ex) 50.0)))}
                  :cunning {:desc (txt "tagCharAttributeDescription01")
                            :bonusHealth (bonus-health :cunning 1.0)}
                  :spirit {:desc (txt "tagCharAttributeDescription03")
                           :bonusHealth (bonus-health :spirit 1.5)
                           :bonusEnergy (Math/round (* 2.0 (- (:spirit ex) 50.0)))}
                  :health {:desc (txt "tagCharAttributeDescription04")}
                  :energy {:desc (txt "tagCharAttributeDescription05")
                           :reserved (:energy-reserved st)}
                  :oa {:desc (txt "tagCharStatsOADescription")}
                  :da {:desc (txt "tagCharStatsDADescription")}
                  :armour {:desc (txt "tagCharStatsArmorTotalDescription")
                           ;; 70% is the engine's base absorption
                           :absorption (Math/round (* 70.0 (+ 1.0 (/ (double (get tot "defensiveAbsorptionModifier" 0.0)) 100.0))))}
                  :split {:physique (split :physique (:physique st))
                          :cunning (split :cunning (:cunning st))
                          :spirit (split :spirit (:spirit st))
                          :health [base-health (- (:health st) base-health)]
                          :energy [base-energy (- (:energy st) base-energy)]}})
               :equipment (into equip weapons)})))

(defn tree-data
  "Both mastery trees: every node, its level, and where the game's UI puts it."
  [character]
  (let [c character
        name-of (fn [r] (let [t (some-> r dbu/skill-display-name)]
                          (str (or (get (dbu/localization-table) t) t))))
        ;; A skill whose effect is a buff or a pet bonus keeps almost nothing on
        ;; its own record: no display name, no skillMaxLevel, no icon, and none
        ;; of the values that scale with rank. All of it lives on the record
        ;; named by buffSkillName or petSkillName. Ten of this character's
        ;; twenty-one mastery skills are built that way -- Curse of Frailty and
        ;; Blood of Dreeg through a buff, Raging Tempest and the pet modifiers
        ;; through a pet -- so dropping nodes for having no max level lost every
        ;; one of them.
        via (fn [r] (or (some-> r (get "buffSkillName") not-empty str dbu/record-by-name)
                        (some-> r (get "petSkillName") not-empty str dbu/record-by-name)))
        ;; and the chain can be more than one link: Hellfire is a pet skill whose
        ;; own record is a shell naming a buff, so following once finds another
        ;; nameless record. Follow until something carries both a name and a cap.
        real (fn [r]
               (loop [x r, n 0]
                 (cond
                   (or (nil? x) (>= n 4)) r
                   (and (seq (name-of x))
                        (pos? (long (or (get x "skillMaxLevel") 0)))) x
                   :else (if-let [nx (via x)] (recur nx (inc n)) r))))
        display (fn [rec] (name-of (real rec)))
        ;; what the character has put points into, by record path
        spent (into {} (for [s (:skills c) :when (pos? (long (or (:level s) 0)))]
                         [(str/lower-case (str (:skill-name s))) (long (:level s))]))
        ;; How many levels gear adds to a skill. resistances works this out
        ;; already -- for every skill, for every mastery, and for all skills at
        ;; once -- so it is reached through rather than repeated.
        equipped* (requiring-resolve 'gd-edit.resistances/equipped)
        level-bonuses* (requiring-resolve 'gd-edit.resistances/level-bonuses)
        effective-level* (requiring-resolve 'gd-edit.resistances/effective-level)
        raw-level* (requiring-resolve 'gd-edit.resistances/raw-level)
        bonuses (level-bonuses* (equipped* c))
        ;; A record's per-level arrays are indexed from zero, so the values a
        ;; skill has at level N sit at N-1. calc-item-skill-level does the same
        ;; decrement for an item-granted skill.
        ;; How many ranks a record actually holds data for. Asking past the end
        ;; is not an error and not empty: maybe-choose-by-skill-level falls back
        ;; to the first entry, so Summon Briarthorn at rank 27 reported the
        ;; energy cost of rank 1 and the next rank looked cheaper than the
        ;; current one. Gear pushes a skill well past its own cap, so this is
        ;; reached often rather than never.
        ranks (fn [r] (apply max 1 (for [[k v] r :when (and (string? k) (vector? v)
                                                            (every? number? v))]
                                     (count v))))
        at-level (fn [r level blocks]
                   (when (and r (pos? (long level)))
                     (let [lv (min (long level) (ranks r))]
                       (vec (->> (isum/effect-summary (assoc r "itemSkillLevel" (dec lv))
                                                      (or blocks #{}))
                                 flatten (remove nil?) (map str)
                                 (map #(str/replace % #"\s+$" "")))))))
        ;; effect-summary follows buffSkillName and petSkillName but does not
        ;; carry itemSkillLevel across, so the nested record would answer at
        ;; rank 1 whatever rank was asked for. The two halves are summarised
        ;; separately instead: the skill's own line (its cost, duration, radius)
        ;; with the nested branch blocked, then the buff or pet at the same rank.
        skill-lines (fn [r level]
                      (let [inner (real r)]
                        (if (= (:recordname inner) (:recordname r))
                          (at-level r level nil)
                          (vec (concat (at-level r level #{:buff-skill :pet-skill})
                                       (at-level inner level nil))))))
        ;; class skill -> the devotion proc assigned to it. The save records the
        ;; binding on the proc's own entry, pointing the other way, so this is
        ;; the reverse of what devo.clj builds.
        celestial (into {} (for [sk (:skills c)
                                 :let [a (some-> (:autocast-skill-name sk) not-empty str)]
                                 :when a
                                 :let [pr (dbu/record-by-name a)
                                       ;; the proc's own level lives on its star
                                       plvl (some (fn [x] (when (= (str/lower-case (str (:skill-name x)))
                                                                   (str/lower-case a))
                                                            (long (or (:level x) 0))))
                                                  (:skills c))]]
                             [(str/lower-case (str (:skill-name sk)))
                              {:name (display pr)
                               ;; no level line: these records are skillMaxLevel 1,
                               ;; so there is no rank to report
                               :trigger (some-> (:autocast-controller-name sk) not-empty str
                                                (str/replace #".*/cast_@" "")
                                                (str/replace #"\.dbr$" ""))
                               :chance (some-> (:autocast-controller-name sk) not-empty str
                                               dbu/record-by-name (get "chanceToRun"))
                               :lines (skill-lines pr (or plvl 0))}]))
        masteries (for [s (:skills c)
                        :let [r (dbu/record-by-name (:skill-name s))]
                        :when (= "Skill_Mastery" (str (get r "Class")))]
                    {:record r :skill s
                     :class (second (re-find #"class(\d+)" (str (:skill-name s))))})
        trees
        (for [{:keys [record skill class]} masteries
              :let [table (dbu/record-by-name (str "records/ui/skills/class" class "/classtable.dbr"))
                    buttons (get table "tabSkillButtons")]
              :when buttons]
          (let [nodes (for [b buttons
                            :let [ui (dbu/record-by-name (str b))
                                  sn (some-> ui (get "skillName") not-empty str)
                                  sr (some-> sn dbu/record-by-name)]
                            :when (and ui sr (get ui "bitmapPositionX"))
                            :let [nm (display sr)
                                  ;; a mastery bar's own button is the class training
                                  ;; entry; it is the bar, not a skill, so it is left out
                                  mastery? (= "Skill_Mastery" (str (get sr "Class")))]
                            :when (and (seq nm) (not mastery?))]
                        (let [pts (long (get spent (str/lower-case sn) 0))
                              eff (long (effective-level* bonuses sn pts))
                              ;; bonuses that run past the ceiling are wasted,
                              ;; which is the case the game prints in red --
                              ;; reaching the ceiling exactly is not
                              pinned (> (long (raw-level* bonuses sn pts)) eff)
                              ;; the cap, the icon and the description all come
                              ;; from whichever record actually carries them
                              sr* (real sr)
                              cap (long (or (get sr* "skillMaxLevel") 0))]
                          {:name nm
                           :x (long (get ui "bitmapPositionX"))
                           :y (long (get ui "bitmapPositionY"))
                           :circular (= 1 (long (or (get ui "isCircular") 0)))
                           :level pts
                           :max cap
                           :ultimate (long (or (get sr* "skillUltimateLevel") 0))
                           :pinned pinned
                           :tex (some-> (get sr* "skillUpBitmapName") not-empty str)
                           :desc (some-> (dbu/skill-description-from-record sr*) str not-empty)
                           ;; what gear adds on top of the points spent
                           :gear (- eff pts)
                           :cur (when (pos? pts) (skill-lines sr eff))
                           ;; the game shows the next rank so the cost of a point
                           ;; is visible -- but only where there is one: neither
                           ;; past the skill's own cap nor past the last rank the
                           ;; record carries values for
                           :next (when (and (pos? pts) (< pts cap) (< eff (ranks sr*)))
                                   (skill-lines sr (inc eff)))
                           :celestial (get celestial (str/lower-case sn))}))
                nodes (vec (remove #(zero? (:max %)) nodes))]   ; :max is the resolved cap
            {:mastery (str (get table "skillTabTitle"))
             :level (long (or (:level skill) 0))
             :nodes nodes
             :minX (apply min (map :x nodes)) :maxX (apply max (map :x nodes))
             :minY (apply min (map :y nodes)) :maxY (apply max (map :y nodes))}))
        ;; devotions: only real constellations, not the skills they grant. The
        ;; constellation records name themselves in constellationDisplayTag.
        constellation-names
        (set (for [r (dbu/db)
                   :when (re-find #"/constellations/constellation\d+\.dbr$" (str (:recordname r)))
                   :let [n (some-> (get r "constellationDisplayTag") not-empty str)]
                   :when n]
               n))
        devo (->> (:skills c)
                  (filter #(and (:enabled %) (pos? (long (or (:devotion-level %) 0)))
                                (pos? (long (or (:level %) 0)))))
                  (keep #(let [n (display (dbu/record-by-name (:skill-name %)))]
                           (when (contains? constellation-names n) n)))
                  frequencies
                  (sort-by key)
                  (mapv (fn [[n stars]] {:name n :stars stars})))]
    (identity {:trees (vec trees) :constellations devo})))

(defn devotion-data
  "The constellations taken, their stars, links, affinities and bindings."
  [character]
  (let [c character
        ;; some stars carry no display name of their own -- Solael's Witchblade's
        ;; grant is one -- and name only the buff they apply, so fall back to that
        display (fn [rec]
                  (let [direct (let [t (some-> rec dbu/skill-display-name)]
                                 (str (or (get (dbu/localization-table) t) t)))]
                    (if (str/blank? direct)
                      (let [b (some-> rec (get "buffSkillName") not-empty str dbu/record-by-name)
                            t (some-> b dbu/skill-display-name)]
                        (str (or (get (dbu/localization-table) t) t)))
                      direct)))
        ;; every devotion star the character has actually taken, by record path
        taken (set (for [s (:skills c)
                         :when (and (:enabled s)
                                    (pos? (long (or (:devotion-level s) 0)))
                                    (pos? (long (or (:level s) 0))))]
                     (str/lower-case (str (:skill-name s)))))
        ;; skill-record -> the class skill it is assigned to, from :autocast-skill-name
        bound (into {} (for [sk (:skills c)
                             :let [a (some-> (:autocast-skill-name sk) not-empty str)]
                             :when a]
                         [(str/lower-case a)
                          {:name (display (dbu/record-by-name (:skill-name sk)))
                           ;; Curse of Frailty carries neither name nor icon on its
                           ;; own record and defers both to its buff, as some
                           ;; devotion stars do
                           :tex (let [r (dbu/record-by-name (:skill-name sk))]
                                  (or (some-> r (get "skillUpBitmapName") not-empty str)
                                      (some-> r (get "buffSkillName") not-empty str
                                              dbu/record-by-name
                                              (get "skillUpBitmapName") not-empty str)))
                           :trigger (some-> (:autocast-controller-name sk) not-empty str
                                            (str/replace #".*/cast_@" "")
                                            (str/replace #"\.dbr$" ""))}]))
        ;; The game's constellation tooltip prints one "Total" -- every star's
        ;; bonus added together -- rather than the stars one by one, and a
        ;; matching total for pets. Summing the records' numeric fields and
        ;; phrasing the result once reproduces that: effect-summary ignores any
        ;; field that is not a stat, so the non-stat numbers carried along do no
        ;; harm.
        nums (fn [r] (into {} (for [[k v] r :when (and (string? k) (number? v))] [k v])))
        sum (fn [ms] (let [ms (remove empty? ms)]
                       (when (seq ms) (apply merge-with + ms))))
        summary (fn [m] (when m
                          (vec (->> (isum/effect-summary m) flatten (remove nil?) (map str)
                                    (map #(str/replace % #"\s+$" ""))
                                    (remove str/blank?)))))
        consts
        (for [r (dbu/db)
              :when (re-find #"/constellations/constellation\d+\.dbr$" (str (:recordname r)))
              :let [nm (some-> (get r "constellationDisplayTag") not-empty str)
                    bg (some-> (get r "constellationBackground") not-empty str dbu/record-by-name)
                    bmp (some-> bg (get "bitmapName") not-empty str)
                    bx (some-> bg (get "bitmapPositionX") long)
                    by (some-> bg (get "bitmapPositionY") long)
                    stars (for [i (range 1 13)
                                :let [b (some-> (get r (str "devotionButton" i)) not-empty str dbu/record-by-name)
                                      sn (some-> b (get "skillName") not-empty str)
                                      sr (some-> sn dbu/record-by-name)]
                                :when (and b sn sr (get b "bitmapPositionX"))]
                            {:name (display sr)
                             ;; which devotionButton this is, so the links can
                             ;; name their endpoints
                             :button i
                             :x (long (get b "bitmapPositionX"))
                             :y (long (get b "bitmapPositionY"))
                             ;; a star that grants a skill is the constellation's
                             ;; payoff -- the game draws it larger and it is what
                             ;; people mean by "the skill at the end"
                             :skill (boolean (or (get sr "buffSkillName")
                                                 (not= "Skill_Passive" (str (get sr "Class")))))
                             :taken (contains? taken (str/lower-case sn))
                             ;; a skill star's icon is the constellation's button
                             ;; art, which is what the game shows for that skill
                             ;; and, like the name, it may live on the buff instead
                             :bound (get bound (str/lower-case sn))
                             :tex (or (some-> sr (get "skillUpBitmapName") not-empty str)
                                      (some-> sr (get "buffSkillName") not-empty str
                                              dbu/record-by-name
                                              (get "skillUpBitmapName") not-empty str))})]
              :when (and nm (seq stars))]
          (let [star-recs (keep (fn [i]
                                  (some-> (get r (str "devotionButton" i)) not-empty str
                                          dbu/record-by-name
                                          (get "skillName") not-empty str
                                          dbu/record-by-name))
                                (range 1 13))]
            {:name nm :bitmap bmp :bgX bx :bgY by
             :stars (vec stars)
             ;; the game joins its stars: devotionLinksN names the button (or
             ;; buttons) that button N connects to
             :links (vec (for [i (range 1 13)
                               :let [v (get r (str "devotionLinks" i))]
                               :when v
                               t (if (sequential? v) v [v])
                               :when (pos? (long t))]
                           [i (long t)]))
             :desc (some-> (get r "constellationInfoTag") not-empty str)
             :total (summary (sum (map nums star-recs)))
             :petTotal (summary (sum (for [sr star-recs
                                           :let [pb (some-> (get sr "petBonusName") not-empty str
                                                            dbu/record-by-name)]
                                           :when pb]
                                       (nums pb))))
             ;; what has to be spent elsewhere before this one opens
             :required (vec (for [i (range 1 4)
                                  :let [a (get r (str "affinityRequiredName" i))
                                        n (get r (str "affinityRequired" i))]
                                  :when (and a n (pos? (long n)))]
                              {:name (str a) :points (long n)}))
             :affinity (vec (for [i (range 1 4)
                                  :let [a (get r (str "affinityGivenName" i))
                                        n (get r (str "affinityGiven" i))]
                                  :when (and a n (pos? (long n)))]
                              {:name (str a) :points (long n)}))}))
        mine (filter #(some :taken (:stars %)) consts)]
    (identity
              {:constellations
               (vec (for [k (sort-by :name mine)]
                      (assoc k :starsTaken (count (filter :taken (:stars k)))
                               :starsTotal (count (:stars k))
                               :complete (every? :taken (:stars k)))))
               ;; affinity is granted by completing a constellation, and is what
               ;; the game's Affinities panel counts
               :affinities
               (->> mine
                    (filter #(every? :taken (:stars %)))
                    (mapcat :affinity)
                    (reduce (fn [m {:keys [name points]}] (update m name (fnil + 0) points)) {})
                    (sort-by (comp - val))
                    (mapv (fn [[n p]] {:name n :points p})))})))

(defn buff-data
  "What is running on the character: auras, activated skills and procs."
  [character]
  (let [c character
        display (fn [r] (let [t (some-> r dbu/skill-display-name)
                              d (str (or (get (dbu/localization-table) t) t))]
                          (if (str/blank? d)
                            (let [b (some-> r (get "buffSkillName") not-empty str dbu/record-by-name)
                                  t2 (some-> b dbu/skill-display-name)]
                              (str (or (get (dbu/localization-table) t2) t2)))
                            d)))
        icon-of (fn [r] (or (some-> r (get "skillUpBitmapName") not-empty str)
                            (some-> r (get "buffSkillName") not-empty str dbu/record-by-name
                                    (get "skillUpBitmapName") not-empty str)))
        ;; which mastery a class skill belongs to, so an entry can say where it came from
        mastery-of (into {} (for [s (:skills c)
                                  :let [r (dbu/record-by-name (:skill-name s))]
                                  :when (= "Skill_Mastery" (str (get r "Class")))
                                  :let [cls (second (re-find #"class(\d+)" (str (:skill-name s))))]]
                              [cls (display r)]))
        item-name (fn [it] (str (dbu/item-name it (dbu/db-and-index))))
        worn (->> (concat (:equipment c) (->> (:weapon-sets c) (remove :unused) (mapcat :items)))
                  (filter #(not-empty (str (:basename %)))))
        entry (fn [r src & [extra]]
                (merge {:name (display r) :source src :tex (icon-of r)} extra))
        dur (fn [r lvl] (let [d (get r "skillActiveDuration")]
                          (cond (number? d) (double d)
                                (sequential? d) (double (nth d (max 0 (min (dec (count d)) (dec (long (or lvl 1))))) 0))
                                :else nil)))
        ;; the character's own skills
        own (for [s (:skills c)
                  :when (and (pos? (long (or (:level s) 0)))
                             (zero? (long (or (:devotion-level s) 0))))
                  :let [r (dbu/record-by-name (:skill-name s))
                        cls (str (get r "Class"))
                        m (get mastery-of (second (re-find #"class(\d+)" (str (:skill-name s)))))
                        potion? (str/includes? (str (:skill-name s)) "potionmodifiers")]
                  :when (and r (seq (display r))
                             (or (toggled cls) (cast-buff cls))
                             (not potion?))]
              (assoc (entry r (str "Mastery – " (or m "?")) {:duration (dur r (:level s))})
                     :group (if (toggled cls) "permanent" "activated")
                     :on (boolean (:skill-active s))))
        ;; buffs an item or component grants
        granted (for [it worn
                      k [:basename :relic-name :augment-name]
                      :let [src (some-> (get it k) not-empty str dbu/record-by-name)
                            sn (some-> src (get "itemSkillName") not-empty str)
                            r (some-> sn dbu/record-by-name)]
                      :when (and r (seq (display r)))
                      :let [cls (str (get r "Class"))
                            label (if (= k :basename) (item-name it)
                                      (dbu/item-base-record-get-name src))]
                      :when (or (toggled cls) (cast-buff cls))]
                  (assoc (entry r (str "Item – " label) {:duration (dur r 1)})
                         :group (if (toggled cls) "permanent" "triggered")
                         :on (boolean (toggled cls))))
        ;; devotion procs, with the trigger their binding carries
        procs (for [s (:skills c)
                    :let [a (some-> (:autocast-skill-name s) not-empty str)
                          r (some-> a dbu/record-by-name)
                          ctrl (some-> (:autocast-controller-name s) not-empty str dbu/record-by-name)]
                    :when (and r (seq (display r)))]
                (assoc (entry r (str "Devotion") {:duration (dur r 1)
                                                  :trigger (trigger-text ctrl)
                                                  :boundTo (display (dbu/record-by-name (:skill-name s)))})
                       :group "triggered" :on false))
        all (concat own granted procs)]
    (identity
              {:permanent (vec (filter #(= "permanent" (:group %)) all))
               :activated (vec (filter #(= "activated" (:group %)) all))
               :triggered (vec (filter #(= "triggered" (:group %)) all))})))
