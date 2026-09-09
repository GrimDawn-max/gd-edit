(ns gd-edit.commands.create-character
  (:require [clojure.data.json :as json]
            [gd-edit.utils :as u]
            [gd-edit.io.gdc :as gdc]
            [gd-edit.app-util :as au]
            [com.rpl.specter :as s]
            [gd-edit.commands.class :as class-cmds]
            [gd-edit.globals :as globals]
            [gd-edit.skill :as skill]
            [gd-edit.commands.item :as item]
            [gd-edit.commands.set :as commands.set]
            [gd-edit.commands.level :as level]
            [gd-edit.db-utils :as dbu]
            [gd-edit.max-rolls :as max-rolls]
            [gd-edit.item-stats :as item-stats]
            [clojure.java.io :as io]

            [clojure.pprint :refer [pprint]]
            [me.raynes.fs :as fs]
            [gd-edit.jline :as jl]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.printer :as printer]
            [gd-edit.db-query :as query]
            [clojure.string :as str]
            [clojure.data]
            [gd-edit.gt-character-spec :as gt-char-spec]
            [clojure.spec.alpha :as spec]))


(defn ggd-classes
  [gt-character]

  (:classes gt-character))

(defn ggd-apply-classes
  [gt-character-classes character]

  (reduce #(class-cmds/class-add-by-name % (:name %2) (:level %2)) character gt-character-classes))

(defn attribute-points-required
  "For any attribute, calcuate how many attribute points are required to reach the given target value.

  Example: How many skill points does it need to raise physique to 450?
  => 50"
  [target-val]

  (-> target-val
      (- 50)
      (/ 8)))

(defn gt-apply-attributes
  [gt-character-attributes character]

  (let [character (cond-> character
                    (:physique gt-character-attributes)
                    (assoc :physique (:physique gt-character-attributes))

                    (:cunning gt-character-attributes)
                    (assoc :cunning (:cunning gt-character-attributes))

                    (:spirit gt-character-attributes)
                    (assoc :spirit (:spirit gt-character-attributes))

                    (:devotionPoints gt-character-attributes)
                    (assoc :devotion-points (:devotionPoints gt-character-attributes)))]

    (update character :attribute-points #(dbu/coerce-to-type
                                          (max (- %
                                                  (attribute-points-required (:physique character))
                                                  (attribute-points-required (:cunning character))
                                                  (attribute-points-required (:spirit character)))
                                               0)
                                          (type %)))))

(defn ggd-apply-skills
  [ggd-character-skills character]

  ;; Entries in the gt-character-skills array may have a single layer of child skills.
  ;; Flatten that into a single list so we can add the skills more easily.
  (let [ggd-character-skills (->> ggd-character-skills
                                 (s/select [s/ALL :children])
                                 (apply concat ggd-character-skills))]

    (reduce (fn [character ggd-skill]
              (skill/skill-add-by-display-name
               character
               (:name ggd-skill)
               (:level ggd-skill)))
            character
            ggd-character-skills)))

(defn devotion-skill-set-max-level
  [skill]

  (let [skill-record (dbu/devotion-skill-descriptor-by-recordname (:skill-name skill))
        max-level (get skill-record "skillMaxLevel")
        exp-levels (get skill-record "skillExperienceLevels")
        level (max 1 (count exp-levels) (or max-level 0))
        exp (or (last exp-levels) 0)

        display-name (dbu/skill-display-name skill-record)]

    (when (and display-name
               (> level 1))
      (println (format "Setting '%s' to level %d"
                       display-name
                       level)))

    (-> skill
        (assoc :devotion-level level)
        (assoc :devotion-experience exp))))

(defn ggd-apply-devotions
  [gt-devotions character]

  (let [skills-map (dbu/constellation-skills-map)]
    (reduce (fn [character gt-devotion]

              (let [skill-recordname (-> skills-map
                                            (get {:constellation-id (:constellationNumber gt-devotion )
                                                  :button-id (:devotionButton gt-devotion )})
                                            dbu/record-by-name
                                            (get "skillName"))]

                (if (not skill-recordname)
                  (do
                    (println "Oops... unable to locate this devotion in the character file...")
                    (pprint gt-devotion)
                    character)

                  (let [skill-record (dbu/record-by-name skill-recordname)]
                    (-> character
                        (update :skills conj (-> skill/blank-skill
                                               (assoc :skill-name skill-recordname)
                                               (devotion-skill-set-max-level)))
                        (update :devotion-points dec))))))
            character
            gt-devotions)))

(defn gdc-skill-is-celestial-power
  [gdc-skill]
  (contains? (dbu/celestial-power-recordnames-memoized)
             (:skill-name gdc-skill)))

(defn gdc-skill-is-from-constellation-star
  "Check if the skill is something that came from constallation stars, basically anything
  that can be taken by using devotion points.

  It might be a passive or it can be a celetial power"
  [gdc-skill]
  (contains? (dbu/constellation-star-skill-recordnames-memoized)
             (:skill-name gdc-skill)))


(def weapon-set-path {:weapon1 [:weapon-sets 0 :items 0]
                      :weapon2 [:weapon-sets 0 :items 1]
                      :weapon1Alt [:weapon-sets 1 :items 0]
                      :weapon2Alt [:weapon-sets 1 :items 1]})

(def equipment-slot-idx {:head   0
                         :amulet 1
                         :chest  2
                         :legs   3
                         :feet   4
                         :hands  5
                         :ring1  6
                         :ring2  7
                         :waist  8
                         :shoulders 9
                         :medal  10
                         :relic  11})

(def equipment-slot-path
  (merge
   weapon-set-path
   (into {}
         (for [[slot-name idx] equipment-slot-idx]
           [slot-name [:equipment idx]]))))

(defn place-item-in-inventory
  [character path item]

  (if-let [[updated-character actual-path] (item/place-item-in-inventory character path item)]
    updated-character
    character))

(defn gt-apply-item-augment
  [gt-item item]

  ;; Try to look up the augment specified in the gt-item by name
  (if-let [augment-record (get (dbu/augments) (get-in gt-item [:augment :name]))]
    ;; If the augment can be found, add it to the item now
    (-> item
        (assoc :augment-name (:recordname augment-record))
        (assoc :augment-seed (rand-int Integer/MAX_VALUE)))

    ;; Otherwise, just passback the unaltered item
    item))


(defn gt-apply-item-relic
  "Apply relic/component settings to the item"
  [gt-item item]

  ;; Try to look up the augment specified in the gt-item by name
  (if-let [relic-record (get (dbu/relics) (get-in gt-item [:component :name]))]
    ;; If the augment can be found, add it to the item now
    (-> item
        (assoc :relic-name (:recordname relic-record))
        (assoc :relic-seed (rand-int Integer/MAX_VALUE)))

    ;; Otherwise, just passback the unaltered item
    item))


(defn relic-search-by-name
  [relic-name]
  (first (query/query-db (dbu/db) (format "Class=\"ItemArtifact\" value~\"%s\"" relic-name))))

(defn relic-completion-bonus-records
  [relic-record]

  (assert (= (get relic-record "Class") "ItemArtifact"))

  (let [completion-records (as-> relic-record $
                                  (get $ "bonusTableName")
                                  (dbu/record-by-name $)
                                  (dbu/record-fields-starting-with "randomizerName" $)
                                  (map val $)
                                  (map dbu/record-by-name $)
                                  )]
    completion-records))

(defn select-keys-by
  [m pred]

  (->> m
       (reduce (fn [accum kv]
                 (if (pred (key kv))
                   (conj accum kv)
                   accum))
               [])
       (into {})))

(defn relic-bonus-v-normalize
  "We need a way to compare matched relic bonus fields, between what's on a grimtools character and
  what's in the dabase.

  Here, we normalize the representation of the value field so it's easier to determine their equality.
  "
  [v]

  (cond
    (nil? v) v

    ;; Single strings are returned as is
    (string? v) v

    ;; Integers are returned as floats
    (int? v) (float v)

    ;; Floats are returned as is
    (float? v) v

    ;; Vectors should be considered as a set
    (or (vector? v) (list? v)) (set v)

    :else
    (ex-info "Don't know how to handle relic bonus value of this type!" {})))

(defn relic-bonus-v=
  [a b]

  (= (relic-bonus-v-normalize a) (relic-bonus-v-normalize b)))

(defn relic-completion-bonus--match-kv
  "Given a list of relic completion bonus records, and a single k v pair to look for, return the
  record that matches the given `kv`"
  [completions [k v]]

  (reduce (fn [res item]
            (when (relic-bonus-v= (get item k) v)
              (reduced item)))
          nil
          completions))


(defn relic-completion-bonus--match-kvs
  "Given a list of relic completion bonus records, and mutiple k v pairs, `kvs`, to match for, return the
  record that matches the given all kvs pairs"
  [completions kvs]

  (reduce (fn [res item]
            (when (every? (fn [[k v]] (relic-bonus-v= (get item k) v)) kvs)
              (reduced item)))
          nil
          completions))

(defn relic-completion-bonus-skill-augments
  [relic-record]

  (let [bonus-records (relic-completion-bonus-records relic-record)
        augment-records (filter #(dbu/record-fields-starting-with "augmentSkillLevel" %) bonus-records)]
    (apply hash-map
           (interleave
            (->> augment-records
                 ;; Select only kv pairs where the key starts with 'augmentSkillName'
                 (map #(select-keys-by % (fn [k]
                                           (str/starts-with? (str k) "augmentSkillName"))))

                 ;; Each value point to a skill to be augmented
                 ;; Look up the record and turn it into the actual skill name
                 (s/transform [s/ALL s/MAP-VALS] #(dbu/skill-name-from-record (dbu/record-by-name %)))

                 ;; Collect the skill names into a set
                 (map #(set (vals %))))
            (map :recordname augment-records)))))

(defn gt-apply-artifact-completion-bonus
  "Apply the completion bonus for a thing in the `relic` equipment slot"
  [gt-item item]

  (if-not (= "relic" (:slot gt-item))
    item

    (let [relic-record (dbu/record-by-name (:basename item))
          bonus-spec (get-in gt-item [:completionBonus :completionBonuses])
          bonus-recordname (if (get-in gt-item [:completionBonus :isClassRelic])
                             (get (relic-completion-bonus-skill-augments relic-record) (set bonus-spec))

                             ;; The bonus spec should be a bunch of kv pairs that needs to all match to
                             ;; uniquely identify a single bonus record for this relic

                             ;; Make sure our key names are strings instead of keywords
                             ;; This needs to happen because the database uses strings as keys
                             (->> (s/transform [s/MAP-KEYS] name bonus-spec)
                                  ;; Grab a list of bonus records
                                  ;; Try to find one that matches all of the criteria
                                  (relic-completion-bonus--match-kvs (relic-completion-bonus-records relic-record))
                                  :recordname))]
      (cond-> item
        bonus-recordname (assoc :relic-bonus bonus-recordname))
      )))

(defn ggd-apply-equipment
  [gt-character-equipments character]

  ;; Try to add each piece of equipment onto the character
  (reduce (fn [character gt-item]
            ;; Try to construct the item
            (let [slot-name (:slot gt-item)
                  path (equipment-slot-path (keyword slot-name))
                  _ (println (str "Generating item for: " slot-name))

                  character-level (:character-level character)
                  item-name (:name gt-item)

                  ;; Try to to create the requested item at the character level
                  item (item/construct-item item-name character-level)

                  ;; if that's not possible, try to create then item with no level restrictions
                  item (if (some? item)
                         item
                         (do
                           (println (format "Could not create item matching character level: %d" character-level))
                           (println "Trying again with no level restrictions")
                           (item/construct-item item-name nil)))]

              (cond

                ;; If it's just impossible to create the item, do nothing and return the character unaltered
                (or (nil? item)
                    (not (item/item-names-similiar (:name gt-item) (dbu/item-name item))))
                (do
                  (println (format "Could not create item: %s" item-name))
                  character)

                :else
                (let [item (->> item
                                (gt-apply-item-augment gt-item)
                                (gt-apply-item-relic gt-item)
                                (gt-apply-artifact-completion-bonus gt-item))
                        ;; Some gt-items may come with specifc prefix and suffix recordnames
                      item (cond-> item
                             (:prefix gt-item) (assoc :prefix-name (:prefix gt-item))
                             (:suffix gt-item) (assoc :suffix-name (:suffix gt-item)))]

                  ;; Try to place the item onto the character
                  (if-let [updated-character (place-item-in-inventory character path item)]
                      ;; If the update failed for some reason, just return the original (un-altered) character
                    updated-character
                    character)))))

          character gt-character-equipments))

;; Given some equipment definition that comes from gt directly...
;; Refine an existing already generated item...
;;
;; Expects input in the shape of:
;;
;; {:item "records/items/gearrelic/d201_relic.dbr",
;;  :relicBonus "records/items/lootaffixes/completionrelics/aoathkeeper_19a.dbr"}
(defn gt-equipment-refine
  "Given some equipment definition that comes from gt directly, refine an existing item.
  More specifically, make sure we are using the exact prefix and suffix"
  [gt-character-data-equipment item]

  (let [mappings {:item :basename
                  :prefix :prefix-name
                  :suffix :suffix-name
                  :component :relic-name
                  :augment :augment-name
                  :relicBonus :relic-bonus
                  :ascendedAffix :ascended-name}

        ;; The item spec is open, so an unrecognised field validates happily and
        ;; is then dropped here without a word -- producing a character that looks
        ;; right and is quietly missing a stat source. ascendedAffix is imported
        ;; now, but keep the warning for whatever comes next.
        _ (doseq [k (keys gt-character-data-equipment)
                  :when (and (not (contains? mappings k))
                             (re-find #"(?i)ascend" (name k)))]
            (println (format "Note: this character has an item field \"%s\" that gd-edit does not import."
                             (name k)))
            (println "      The ascended bonus on that item will be missing."))

        ;; Translate the gt equipment into a gdc item
        item (reduce (fn [item [def-k new-val]]
                       (cond-> item
                  ;; If we know apply a definition onto a field...
                         (mappings def-k)
                  ;; Put the new value into the correct corresponding field
                         (assoc (mappings def-k) new-val)))
                     item
                     gt-character-data-equipment)]

    ;; Update the seeds if need be
    (cond-> item
      :always
      (assoc :seed (rand-int Integer/MAX_VALUE))

      (:augment-name item)
      (assoc :augment-seed (rand-int Integer/MAX_VALUE))

      (:relic-name item)
      (assoc :relic-seed (rand-int Integer/MAX_VALUE)))))


(defn gt-apply-equipment-refine
  [gt-character-data-equipments character]

  (reduce (fn [character [slot-name item-def]]
            (let [path (equipment-slot-path slot-name)]
              (update-in character path #(gt-equipment-refine item-def %))))
          character
          gt-character-data-equipments))

(def gt-apply-equipment gt-apply-equipment-refine)

(defn find-skill-idx-by-recordname
  [character skill-recordname]
  (u/first-match-position (fn [skill]
                            (= (:skill-name skill) skill-recordname))
                          (:skills character)))

(defn find-skill-path-by-recordname
  [character skill-recordname]
  (if-let [idx (find-skill-idx-by-recordname character skill-recordname)]
    [:skills idx]
    nil))

(defn skill-attach-autocast-controller
  [skill]

  (if-not (:autocast-skill-name skill)
    skill

    (let [;; Look up the skill record
          record (dbu/devotion-skill-descriptor-by-recordname (:autocast-skill-name skill))]

      ;; Apply the "templateAutoCast" to the :autocast-controller-name field
      ;; The two :autocast-* fields work together to indicate that a skill is bound
      ;; and what what frequency the skill should be triggerred
      (if-let [controller-name (get record "templateAutoCast")]
        (assoc skill :autocast-controller-name controller-name)
        skill))))

(defn gt-skill-refine
  [gt-character-data-skill skill]
  (let [mappings {:autoCastSkill :autocast-skill-name}]
      (reduce (fn [skill [def-k new-val]]
                (cond-> skill
                  ;; If we know apply a definition onto a field...
                  (mappings def-k)
                  ;; Put the new value into the correct corresponding field
                  (assoc (mappings def-k) new-val)

                  ;; Id the definition asking to set the auto cast skill?
                  (= def-k :autoCastSkill)
                  (skill-attach-autocast-controller)))

              skill
              gt-character-data-skill)))

(defn gt-apply-skill-refine
  [gt-character-data-skills character]
  (reduce (fn [character skill-def]
              (let [path (find-skill-path-by-recordname character (:name skill-def))]
                (update-in character path #(gt-skill-refine skill-def %))))
            character
            gt-character-data-skills))

(defn prompt-set-character-name
  [character]

  (println)
  (let [character-name (jl/readline "Give the character a name: ")]
    (if (not-empty character-name)
      (update character :character-name (constantly character-name))
      character)))

(defn prompt-set-character-level
  [character]

  (binding [gd-edit.globals/character (atom character)]
    (loop []
      (println)
      (let [character-level (jl/readline "Input the character level: ")
            result (level/level-handler ["" [character-level]])]
        (if (not= result :ok)
          (recur)
          @globals/character)))))

(defn set-character-level
  [character-level character]

  (binding [gd-edit.globals/character (atom character)]
    (println)
    (let [result (level/level-handler ["" [character-level]])]
      @globals/character)))

(defn println-passthrough-last
  [text passthrough]
  (println text)
  passthrough)

(defn cap-min-to-zero
  [field-keyword character]

  (update character field-keyword #(max % 0)))

;;---------------------------------------------------------------
;; Skills related functions
;;---------------------------------------------------------------

(defn mk-skill
  [gt-character-skill]

  (let [;; gt-character field -> gd-edit character fields
        mapping {:name :skill-name
                 :level :level
                 :autoCastSkill :autocast-skill-name}
        skill (reduce (fn [character [field-name val]]
                   (cond-> character
                     (mapping field-name)
                     (assoc (mapping field-name) val)))
                 skill/blank-skill
                 gt-character-skill)
        skill (skill-attach-autocast-controller skill)
        ]

    ;; If we've countered a regular skill and there is no reason to do
    ;; anything further
    (if-not (gdc-skill-is-from-constellation-star skill)
      skill

      (if (gdc-skill-is-celestial-power skill)
        ;; - for celestial powers, set level to max
        (devotion-skill-set-max-level skill)

        ;; - for stars, set devotion level to 1
        (assoc skill :devotion-level 1)))
    )
  )

(comment
  (mk-skill
   {:name "records/skills/playerclass09/summon_celestialguardian1.dbr",
    :level 1,
    :autoCastSkill "records/skills/devotion/tier3_20e_skill.dbr"})

  (mk-skill
   {:level 1, :name "records/skills/devotion/tier3_20e_skill.dbr"})

  )

(defn gt-apply-skills
  [gt-character-skills character]

  ;; Entries in the gt-character-skills array may have a single layer of child skills.
  ;; Flatten that into a single list so we can add the skills more easily.
  (reduce (fn [character gt-skill]
            (skill/skill-add
             character
             (mk-skill gt-skill)))
          character
          gt-character-skills))

(defn fetch-gt-character
  [char-id]
  (-> (u/fetch-json-from-url (format "https://grimtools.com/get_build_data.php?id=%s" char-id))
      (assoc :character-id char-id)))


(defn load-ggd-file
  [filepath]
  (u/load-json-file filepath))

(defn from-ggd-character-file
  [json-file template-character]
  (let [;; This is the character we want to end up with
        ggd-character (json/read-json (slurp json-file) true)

        gt-character (:data ggd-character)

        ;; Apply various settings from the json character file
        character (->> template-character
                       skill/skills-remove-all
                       prompt-set-character-name
                       prompt-set-character-level
                       (println-passthrough-last "")

                       (ggd-apply-classes (ggd-classes ggd-character))
                       (gt-apply-attributes (:bio gt-character))

                       (ggd-apply-skills (:skills ggd-character))
                       (ggd-apply-devotions (:devotionNodes ggd-character))
                       (println-passthrough-last "")

                       (ggd-apply-equipment (:items ggd-character))
                       (cap-min-to-zero :attribute-points)
                       (cap-min-to-zero :skill-points))]
    (cond->> character
      ;; If we have some equipment data that comes directly from grimtools...
      ;; Apply the exact records that should be used for the equipment
      (:equipment gt-character)
      (gt-apply-equipment-refine (:equipment gt-character))
      (:skills gt-character)
      (gt-apply-skill-refine (:skills gt-character)))))


(defn gt-apply-character
  [gt-character template-character]

  ;; Apply various settings from the json character file
  (->> template-character
       skill/skills-remove-all
       prompt-set-character-name
       (set-character-level (str (get-in gt-character [:bio :level])))
       (println-passthrough-last "")

       (gt-apply-attributes (:bio gt-character))

       (gt-apply-skills (:skills gt-character))
       (println-passthrough-last "")

       (gt-apply-equipment (:equipment gt-character))
       (cap-min-to-zero :attribute-points)
       (cap-min-to-zero :skill-points)))



(def ^:private foa-block-versions
  "Block versions a Fangs of Asterkarn save carries, as written by the game.

  The blank character shipped in resources predates the expansion and sits at
  {3 4, 4 6, 8 5, 16 11}. Every field the expansion added is gated on these
  versions, so a character built from that template cannot carry an ascended
  affix: the value is set correctly in memory and then silently dropped at
  serialisation, because the writer is told the block is version 4.

  Grim Dawn upgrades such a save on load, which is why a character made this way
  looks right afterwards apart from the missing affix."
  ;; Only block 3 is raised. It is the one that gates :ascended-name, and it is
  ;; enough: Grim Dawn upgrades the remaining blocks itself when it loads the
  ;; character, exactly as it does for any pre-expansion save. Raising the others
  ;; here means supplying every field they gained, and getting that wrong writes a
  ;; save the game cannot read -- a much worse failure than a block it will
  ;; upgrade on its own.
  {3 11})

(defn- fill-nils
  "Supply `defaults` for keys that are absent *or* nil.

  merge is not enough: a record read while the version gate was closed carries
  the key with a nil value rather than omitting it, and merge lets that nil win."
  [defaults m]
  (reduce (fn [acc [k v]] (if (nil? (get acc k)) (assoc acc k v) acc))
          m defaults))

(defn- ensure-foa-fields
  "Give every record the fields the raised block versions will write.

  None of these default: the writer asserts on nil rather than substituting a
  zero, so anything the older layout omitted has to be supplied explicitly.
  Items, stash tabs and skills each gained fields, and they are scattered
  through the character at different depths, so this walks the whole structure
  rather than naming paths."
  [x]
  (cond
    ;; an item -- block 3 and block 4
    (and (map? x) (contains? x :basename))
    (fill-nils {:ascended-name "" :v11-unk2 0
                :seed-reroll-count 0 :affix-reroll-count 0} x)

    ;; a stash tab -- block 4 gained a five-int trailer per tab
    (and (map? x) (contains? x :width) (contains? x :height))
    (fill-nils {:v11-unk1 0 :v11-unk2 0 :v11-unk3 0 :v11-unk4 0 :v11-unk5 0}
               (into (empty x) (map (fn [[k v]] [k (ensure-foa-fields v)])) x))

    ;; a skill record -- block 8 gained one byte after :enabled
    (and (map? x) (contains? x :skill-name))
    (fill-nils {:v8-unk1 0} x)

    (map? x) (into (empty x) (map (fn [[k v]] [k (ensure-foa-fields v)])) x)
    (vector? x) (mapv ensure-foa-fields x)
    (sequential? x) (map ensure-foa-fields x)
    :else x))

(defn upgrade-to-foa
  "Raise the character's block versions so the expansion's fields are written.

  Done only when the imported build actually needs it -- an ascended affix is the
  one thing that cannot survive the older layout. Upgrading unconditionally would
  hand a save to anyone still on a pre-expansion install that their game could not
  read."
  [character]
  (-> character
      (update :meta-block-list
              (fn [blocks]
                (mapv (fn [b]
                        (if-let [v (foa-block-versions (:meta-block-id b))]
                          (assoc b :version v)
                          b))
                      blocks)))
      ;; block 16 gained two ints of its own, and they sit at the top level
      (->> (fill-nils {:16-unk1 0 :16-unk2 0}))
      ensure-foa-fields))

(defn- needs-foa-layout?
  "Does this build carry anything the pre-expansion layout cannot hold?"
  [gt-character]
  (boolean (some :ascendedAffix (vals (:equipment gt-character)))))

(defn- equipped-items
  "Every path into the character that holds a real equipped item.

  Weapon sets are separate from the equipment list, and both can hold blanks, so
  the paths are gathered rather than assumed."
  [character]
  (concat
   (for [i (range (count (:equipment character)))
         :when (not= "" (str (get-in character [:equipment i :basename])))]
     [:equipment i])
   (for [ws (range (count (:weapon-sets character)))
         i (range (count (get-in character [:weapon-sets ws :items])))
         :when (not= "" (str (get-in character [:weapon-sets ws :items i :basename])))]
     [:weapon-sets ws :items i])))

(defn maximise-rolls
  "Give every equipped item the seed that rolls its stats highest.

  GrimTools says which items a build uses but nothing about how they rolled, so
  make-char assigns each a random seed -- a legitimate item, but an average one.
  This replaces those with the best seed each item can have.

  It is deliberately not the default: a character whose every item rolled
  perfectly is obviously not one that was played for, and that should be the
  caller's choice rather than something that happens quietly."
  [character]
  (let [paths (vec (equipped-items character))]
    (if (empty? paths)
      character
      (do
        (println)
        (println (format "Maximising rolls on %d items. Each is a full sweep of all"
                         (count paths)))
        (println (format "%,d seeds, so this takes a while." 2147483647))
        (println)
        (let [t0 (System/nanoTime)
              result (reduce
                      (fn [ch [i path]]
                        (let [item (get-in ch path)
                              nm (or (dbu/item-name item (dbu/db-and-index))
                                     (:basename item))]
                          (print (format "  [%d/%d] %s ... " (inc i) (count paths) nm))
                          (flush)
                          (let [t (System/nanoTime)
                                better (max-rolls/maximise item)
                                secs (/ (- (System/nanoTime) t) 1e9)]
                            (println (if (= (:seed better) (:seed item))
                                       "no better seed found"
                                       (format "seed %d  (%.0fs)" (:seed better) secs)))
                            (assoc-in ch path better))))
                      character
                      (map-indexed vector paths))]
          (println)
          (println (format "Done in %.0f seconds." (/ (- (System/nanoTime) t0) 1e9)))
          result)))))

(defn unlock-rift-gates
  "Give the character every rift gate, on all three difficulties.

  A character built here starts wherever the template last stood, and can reach
  only the towns whose gates the template happened to hold -- which does not
  include the Fangs of Asterkarn ones. Without them the expansion's map is
  visible but unreachable, and there is no way to travel anywhere to change it.

  Unlocking them leaves the choice of where to be with the player: travel to a
  town once and the game makes it the respawn point from then on."
  [character]
  (let [gates (dbu/get-gates)]
    (if (empty? gates)
      character
      (do
        (println)
        (println (format "Setting all %d rift gates..." (count gates)))
        (reduce (fn [c d] (commands.set/add-all-uids c [:teleporter-points d] gates))
                character
                (range (count (:teleporter-points character))))))))

(defn create-character-
  "Take the json file, recreate the character using a template, then move the character to
  the local save directory
  "
  [gt-character-root & {:keys [max-rolls?]}]
  (let [;; Copy the template character directory to a temporary location on disk
        tmp-dir (fs/temp-dir "gd-edit-char")
        _ (u/copy-resource-files-recursive "_blank_character" tmp-dir)

        ;; Load the template directory
        character-file (io/file tmp-dir "player.gdc")
        template-character (gdc/load-character-file character-file)

        ;; Create a new character from the template
        gt-data (:data gt-character-root)
        new-character (cond-> (gt-apply-character gt-data template-character)
                        (needs-foa-layout? gt-data) upgrade-to-foa
                        true unlock-rift-gates
                        max-rolls? maximise-rolls)

        ;; Save it back into the template files directory
        _ (gdc/write-character-file new-character character-file)

        save-dir (dirs/get-local-save-dir)

        _ (println)
        _ (println "local save dir seems to be: " save-dir)

        character-dir (io/file save-dir (format "_%s" (:character-name new-character)))]

    ;; The template directory now contains the new character
    ;; Move it to the local save dir now
    (println "Saving character to")
    (println "\t" (.getAbsolutePath character-dir))
    (println)

    (when-not (.renameTo tmp-dir character-dir)
      (println "Moving the directory didn't seem to work...")
      (println "Copying and overwriting instead...")
      (fs/copy-dir-into tmp-dir character-dir)
      (fs/delete-dir tmp-dir))

    (when-not (.exists character-dir)
      (println "Oops! Unable to save the character to the destination for some reason..."))

    (io/file character-dir "player.gdc")))


(defn create-character
  "Take the json file, recreate the character using a template, then move the character to
  the local save directory
  "
  [gt-character-root & {:keys [max-rolls?]}]
  (if-not (spec/valid? :gt-char/data (:data gt-character-root))
    (do
      (println "Input doesn't look like a valid grimtools character file")
      nil)
    (create-character- gt-character-root :max-rolls? max-rolls?)))

(defn create-character-from-str
  "Take the json file, recreate the character using a template, then move the character to
  the local save directory
  "
  [json-str]

  (-> (json/read-json json-str true)
      create-character))

(defn create-character-from-file
  "Take the json file, recreate the character using a template, then move the character to
  the local save directory
  "
  [json-filepath]
  (-> (slurp json-filepath)
      create-character-from-str))

(defn extract-character-id
  [url-or-id]

  (let [regex #"https:\/\/www\.grimtools\.com\/calc\/(.+)\/?"
        matches (re-matches regex url-or-id)]

    (if matches
      (second matches)
      url-or-id)))

(defn fetch-gt-character
  [character-id]
  (u/fetch-json-from-url (str "https://www.grimtools.com/get_build_data.php?id=" character-id)))


(defn- gt-character-file
  "The argument as a local json file, or nil if it does not name one.

  Some users sit behind a Cloudflare anti-bot challenge on grimtools.com that
  gd-edit cannot clear but their browser can. Accepting a build saved straight
  from the browser gives them a way through that does not depend on the request
  ever leaving the app. The path has to actually exist to count -- a character
  id must never be mistaken for a filename."
  [url-or-character-id]

  (when (some? url-or-character-id)
    (let [f (io/file (u/expand-home url-or-character-id))]
      (when (.isFile f)
        f))))

(defn- read-saved-build
  "Read a build saved from the browser, or explain what went wrong.

  The address people are sent to serves JSON, but the obvious mistake is to save
  the calculator page instead -- that is the page they were looking at, and it is
  HTML. Parsing it produced \"JSON error (unexpected character: <)\" over a stack
  trace, which tells someone following the workaround nothing about what they did
  wrong or how to put it right."
  [file]
  (println (str "Reading character: " (.getPath file)))
  (try
    (u/load-json-file (.getPath file))
    (catch Throwable _
      (let [head (str/triml (slurp file))
            html? (str/starts-with? head "<")]
        (println)
        (println (if html?
                   "That file is a web page, not build data."
                   "That file is not the build data gd-edit expects."))
        (println)
        (when html?
          (println "It looks like the calculator page was saved. The build data is served")
          (println "from a different address -- it shows as plain text, not as the build:")
          (println)
          (println "    https://www.grimtools.com/get_build_data.php?id=<build-id>")
          (println)
          (println "where <build-id> is the part of the calculator link after /calc/.")
          (println "Save that page and pass the saved file to make-char."))
        nil))))

(defn- print-fetch-blocked-message
  "Explain a refused fetch and point at the way around it.

  grimtools.com sits behind Cloudflare, which serves an anti-bot challenge to
  some visitors. Clearing it means running javascript, so a browser passes and
  gd-edit cannot -- and because the decision is made per visitor and by source
  address, there is no request gd-edit could make instead that would get
  through. Saving the build in the browser sidesteps the whole question, so say
  so here rather than leaving a stack trace as the only clue."
  [character-id]

  (println)
  (println "grimtools.com refused the request (HTTP 403).")
  (println)
  (println "This is normally Cloudflare's anti-bot check. It cannot be cleared from")
  (println "inside gd-edit, but your browser can clear it. To work around it, open")
  (println "this address in your browser and save the page:")
  (println)
  (println (str "    https://www.grimtools.com/get_build_data.php?id=" character-id))
  (println)
  (println "then hand the saved file to make-char:")
  (println)
  (println "    make-char <path-to-saved-file>")
  (println))

(defn- fetch-gt-character-or-explain
  "Fetch the character, or explain a 403 and return nil.

  Only 403 is handled: anything else is a fault worth seeing in full, and
  swallowing it would hide real breakage behind advice that does not apply."
  [character-id]

  (try
    (fetch-gt-character character-id)
    (catch clojure.lang.ExceptionInfo e
      (if (= 403 (:status (ex-data e)))
        (do
          (print-fetch-blocked-message character-id)
          nil)
        (throw e)))))

(defn create-character-handler
  [[_ tokens]]

  (let [flag? (fn [t] (#{"--max-rolls" "-max-rolls" "--maxrolls"} (str/lower-case (str t))))
        max-rolls? (boolean (some flag? tokens))
        url-or-character-id (first (remove flag? tokens))
        max-rolls? (if (and max-rolls? (not (item-stats/available?)))
                     (do (println "--max-rolls needs the item stat engine, which this build does not have.")
                         (println "Creating the character with ordinary random rolls instead.")
                         (println)
                         false)
                     max-rolls?)
        local-file (gt-character-file url-or-character-id)

        [fetch-duration gt-character-json]
        (if local-file
          (u/timed (read-saved-build local-file))
          (let [character-id (extract-character-id url-or-character-id)]
            (println (str "Fetching character: " character-id))
            (u/timed (fetch-gt-character-or-explain character-id))))]

    (when (some? gt-character-json)
      (println (format "%s took %.2f seconds"
                       (if local-file "reading" "fetching")
                       (u/nanotime->secs fetch-duration)))

      ;; create-character returns nil when the json is not a grimtools
      ;; character, having already said so. Loading nil would turn that clear
      ;; message into an exception, which matters more now that make-char takes
      ;; files and can be handed the wrong one.
      (when-let [character-filepath (create-character gt-character-json
                                                      :max-rolls? max-rolls?)]
        (println)
        (println "Loading newly created character...")
        (au/load-character-file character-filepath)))))


(comment
  (create-character-handler [nil ["JVljdR7N"]])

  (def t
    (fetch-gt-character "JVljdR7N"))

  (create-character t)

  (find-skill-idx-by-recordname @globals/character
                                "records/skills/playerclass01/blitz1.dbr")

  (repl/cmd "help")
  (repl/cmd "make-char JVljdR7N")

  (-> @globals/character
      :skills
      (nth 14))

  (require 'repl)

  (let [target-character (repl/load-character-file "DDD")
        ggd-char (json/read-json (slurp (u/expand-home "~/Downloads/charData (4).json")) true)
        updated-character (gt-apply-skill-refine (get-in ggd-char [:data :skills]) target-character)]
    (-> updated-character
        :skills
        (nth 15)))

  (-> (repl/load-character-file "CCC")
      (dissoc :meta-block-list)
      :skills
      (nth 14))

  (-> (repl/load-character-file "DDD")
      (dissoc :meta-block-list)
      :skills
      (nth 15))

  (find-skill-idx-by-recordname (repl/load-character-file "CCC") "records/skills/playerclass01/blitz1.dbr")

  (find-skill-idx-by-recordname (repl/load-character-file "DDD") "records/skills/playerclass01/blitz1.dbr")

  :last-line)

(defn gt-apply-attributes-v2
  [gt-character-attributes character]

  (let [;; gt-character field -> gd-edit character fields
        mapping {:attributePoints :attribute-points
                 :skillPoints :skill-points
                 :devotionPoints :devotion-points
                 :physique :physique
                 :cunning :cunning
                 :spirit :spirit}]
    (reduce (fn [character [gt-attr-name new-val]]
              (cond-> character
                (mapping gt-attr-name)
                (assoc (mapping gt-attr-name) new-val)))
            character
            gt-character-attributes)))



;;------------------------------------------------------------------------------------------------
;;
;; Comment
;;
;;------------------------------------------------------------------------------------------------

(comment
  (def t
    (fetch-gt-character "xZyBgRqN"))

  (def t
    (u/fetch-json-from-url "https://www.grimtools.com/get_build_data.php?id=xZyBgRqN"))

  (-> t
      :data)

  (defn load-template-character
    []
    (gdc/load-character-file (io/file (io/resource "_blank_character/player.gdc"))))

  ;; Make a new character from a template
  (def t (from-ggd-character-file (u/expand-home "~/inbox/charData (4).json") (load-template-character)))

  (let [devotions

        (->> (json/read-json (slurp (u/expand-home "~/inbox/charData (4).json")) true)
             (:devotionNodes))]
    (ggd-apply-devotions devotions {:skills []
                                    :skill-points 1000
                                    :devotion-points 55})
    :ok)

  (def t (gdc/load-character-file (io/file (io/resource "_blank_character/player.gdc"))))

  ;; What does the target character look like?
  (json/read-json (slurp (u/expand-home "~/inbox/charData.json")) true)

  ;; What does the current character look like?
  (reset! globals/character t)

  @globals/character

  (let [character (-> (au/locate-character-files "Odie")
                      first
                      gdc/load-character-file
                      skill/skills-remove-all)]
    character)

  (->> (range 10)
       (drop 1))

  (let [a-map {:skills [:a :b :c :d :e :f :g]}]
    (s/transform [:skills] (fn [v] (take 5 v)) a-map))

  (:skills @globals/character)

  (:skills (json/read-json (slurp (u/expand-home "~/inbox/charData.json")) true))

  (load-ggd-file "~/Desktop/GDChars/xZyBgRqN.json")

  (let [gt-character-skills (load-ggd-file "~/inbox/charData.json")]

    (->> gt-character-skills
         (s/select [s/ALL :children])
         (apply concat gt-character-skills)))

  (:equipment (from-ggd-character-file (u/expand-home "~/inbox/testChar-formatted.json") (load-template-character)))

  (-> (from-ggd-character-file (u/expand-home "~/inbox/testChar-formatted.json") (load-template-character))
      (gdc/write-character-file (u/expand-home "~/inbox/out.gdc")))

  (def j (load-ggd-file "~/Dropbox/Public/GrimDawn/gd-chars/xZyBgRqN.json"))

  (time
   (-> (u/expand-home "~/Dropbox/Public/GrimDawn/gt-chars/xZyBgRqN.json")
       u/load-json-file
       create-character))

  (time
   (-> (u/expand-home "~/Dropbox/Public/GrimDawn/gd-chars/xZyBgRqN.json")
       u/load-json-file
       create-character))

  (json/read-json (slurp (u/expand-home "~/Dropbox/Public/GrimDawn/gt-chars/xZyBgRqN.json")) true)

  (spit (u/expand-home "~/Dropbox/Public/GrimDawn/gt-chars/xZyBgRqN.json") (json/write-str t))

  (dbu/record-by-name "records/skills/devotion/tier1_08e_skill.dbr")

  (require 'repl)

  (repl/init)

  (repl/cmd "load AAA")

  (repl/cmd "set attribute-points 20")
  (repl/cmd "write")

  (repl/cmd "class")
  (repl/cmd "show skills")
  (repl/cmd "show weaponsets")
  (repl/cmd "show skills")
  (repl/cmd "level 100")

  ;;------------------------------------------------------------------------------
  ;; Relic completion bonus forms
  ;;------------------------------------------------------------------------------
  (def t (relic-search-by-name "Deathchill"))

  (-> t
      relic-completion-bonus-records)

  (-> t
      relic-completion-bonus-records
      (relic-completion-bonus--match-kvs {"characterDexterityModifier"  3
                                          "characterIntelligenceModifier" 3
                                          "characterStrengthModifier" 3}))

  (-> t
      relic-completion-bonus-records
      (relic-completion-bonus--match-kvs {"racialBonusRace" ["Race002" "Race012"]
                                          "racialBonusPercentDamage" 8}))

  (def t (relic-search-by-name "Eye of the Storm"))

  (-> t
      relic-completion-bonus-skill-augments)

  (do
    (def j
      (json/read-json (slurp (u/expand-home "~/Downloads/charData (4).json")) true))

    (def t
      (relic-search-by-name "Eye of the Storm")))

  (->> (json/read-json (slurp (u/expand-home "~/inbox/charData-11-f.json")) true)
       :items
       last)

  (-> j :data :skills)

  (gt-apply-artifact-completion-bonus
   (-> j
       :items
       last))

  (do
    (def j
      (json/read-json (slurp (u/expand-home "~/inbox/charData-12-f.json")) true))

    (def t
      (relic-search-by-name "Deathchill")))

  (->> @globals/character
       :equipment
       last)

  ;; Printing the entire character during debugging locks up the repl
  (set! *print-length* 20)

  (let [char1 (-> (repl/load-character-file "AAA")
                  (dissoc :meta-block-list))
        char2 (-> (repl/load-character-file "target")
                  (dissoc :meta-block-list))]
    (->> (clojure.data/diff char1 char2)
         drop-last))

  (let [offset 0
        char1 (as-> (repl/load-character-file "AAA") $
                (dissoc $ :meta-block-list)
                (:skills $)
                (sort-by :skill-name $)
                ;; (drop offset $)
                )
        char2 (as-> (repl/load-character-file "target") $
                (dissoc $ :meta-block-list)
                (:skills $)
                (sort-by :skill-name $)
                ;; (drop offset $)
                )]
    (->> (clojure.data/diff char1 char2)
         (take 2)))

  (binding [gd-edit.io.gdc/*debug* true]
    (let [;; Copy the template character directory to a temporary location on disk
          tmp-dir (fs/temp-dir "gd-edit-char")
          _ (u/copy-resource-files-recursive "_blank_character" tmp-dir)

        ;; Load the template directory
          character-file (io/file tmp-dir "player.gdc")
          template-character (gdc/load-character-file character-file)]

      :ok))

  :last-line)
