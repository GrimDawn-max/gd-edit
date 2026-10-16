(ns gd-edit.commands.respec
  (:require [clojure.string :as str]
            [gd-edit.utils :as u]
            [gd-edit.globals :as globals]
            [gd-edit.printer :as printer]
            [gd-edit.db-utils :as dbu]
            [gd-edit.skill :as skill]
            [clojure.data :refer [diff]]))

(defn skill-is-devotion?
  [skill]

  (str/starts-with? (:skill-name skill) "records/skills/devotion"))

(defn passive-skill? [skill]
  (let [record ((dbu/db-recordname-index) (:skill-name skill))]
    (= (get record "Class") "Skill_Passive")))

(defn disable-devotion-skill [skill]
  ;; If the given skill is not a devotion, do thing...
  (if-not (skill-is-devotion? skill)
    skill
    (cond-> skill
      ;; Passive skills should be marked "not enabled"
      (passive-skill? skill) (assoc :enabled false)

      ;; all skills should indicate no devotion points have been used/spent
      ;; for this skill
      :lastly (assoc :level 0))))

(defn devotion-skill-enabled? [skill]
  (if (passive-skill? skill)
    (= (:enabled skill) true)
    (> (:level skill) 0)))

(defn skills-vec->skills-by-category
  [skills-list]

  {:default (take 5 skills-list)
   :skills (->> (drop 5 skills-list)
                (filter #(not (skill-is-devotion? %))))

   :devotions (->> (drop 5 skills-list)
                   (filter skill-is-devotion? ))})

(defn skills-by-category->skills-vec
  [skills-by-category]

  (->> (concat (:default skills-by-category)
               (:skills skills-by-category)
               (:devotions skills-by-category))
       (into [])))

(defn respec-character-attributes
  [character option]

  (let [base-attr-points 50
        data-record (dbu/record-by-name "records/creatures/pc/playerlevels.dbr")]

    (merge character
           (dbu/coerce-map-numbers-using-reference
            {:physique base-attr-points
             :cunning base-attr-points
             :spirit base-attr-points

             :attribute-points
             (let [points-to-award
                   (+
                    (/ (- (:physique character) base-attr-points)
                       (data-record "strengthIncrement"))
                    (/ (- (:cunning character) base-attr-points)
                       (data-record "dexterityIncrement"))
                    (/ (- (:spirit character) base-attr-points)
                       (data-record "intelligenceIncrement")))]
               (-> (+ (:attribute-points character) points-to-award)
                   (max 0)))}
            character))))

(def ^:private bought-skills
  "The skills a player can actually spend a point on.

  Everything a character knows lives in one list, bought or not, so refunding
  the lot hands out points that were never earned. A level 1 character who has
  spent nothing was being given 4: evade, the two potion charges and a potion
  modifier, none of which cost anything. At level 100 that made respec return
  254 against a maximum of 250.

  Two tests, and the second is the subtle one. A skill must be in a mastery
  tree -- that excludes the four above, which are in no tree. And it must not
  carry `skillSet`, which marks the attacks a shapeshift form hands you while
  you are in it: Ice Talons and Cold Snap for the Wereraven, Feral Claws and
  Rip and Tear for the Werewolf. Those four *are* in the tree data, so the
  first test alone leaves a character 2 over per form used. They are the only
  four of the game's 325 selectable skills carrying the field, so it separates
  them exactly rather than by guesswork.

  Delayed rather than computed at load: it needs the database."
  (delay (set (vals (skill/player-selectable-skills)))))

(defn- bought?
  "Did the player spend a point on this skill?"
  [s]
  (let [n (str (:skill-name s))
        r (dbu/record-by-name n)]
    (or
     ;; points put into a mastery bar are bought, and the bar is not itself a
     ;; tree entry
     (= "Skill_Mastery" (str (get r "Class")))
     (and (contains? @bought-skills n)
          (nil? (get r "skillSet"))))))

(defn respec-character-skills
  [character option]

  (let [skills-by-category (skills-vec->skills-by-category (:skills character))
        skills (:skills skills-by-category)

        ;; Refund only what was paid for
        points-to-award (reduce #(+ % (:level %2))
                                0
                                (filter bought? skills))]

    (merge character
           (dbu/coerce-map-numbers-using-reference
            {;; Refund the points used back to the character
             :skill-points (+ (:skill-points character) points-to-award)

             ;; Remove all skills from the skills vector
             :skills (skills-by-category->skills-vec
                      (dissoc skills-by-category :skills)
                      )}
            character))))

(defn respec-character-devotions
  [character options]

  ;; Grab a list of devtions taken
  (let [skills-by-category (skills-vec->skills-by-category (:skills character))
        devotions (:devotions skills-by-category)]

    (merge character
           (dbu/coerce-map-numbers-using-reference
            {;; Refund 1 devotion point per devotion point spent
             :devotion-points (+ (:devotion-points character)
                                 (->> devotions
                                      (map #(:level %))
                                      (reduce +)))

             ;; Remove or disable all devotions depending on the given option
             :skills (skills-by-category->skills-vec
                      (if (:hard options)
                        (dissoc skills-by-category :devotions)
                        (update skills-by-category
                                :devotions
                                #(map disable-devotion-skill %))))}
            character))))

(defn respec-handler
  [[input tokens]]

  (let [mode (str/lower-case (or (first tokens) "all"))
        valid-modes #{"all" "attributes" "devotions" "skills" "hard"}]

    ;; Sanity check on the respec mode
    (if-not (contains? valid-modes mode)
      (do
        (u/print-line "Please choose from one of the valid respec types:")
        (doseq [r-type (sort valid-modes)]
          (u/print-indent 1)
          (u/print-line r-type)))

      ;; Try to modify the character according to the chosen mocd
      (let [options (when (= mode "hard") {:hard true})
            modified-character
            (cond
              (or (= mode "all")
                  (= mode "hard"))

              (-> @globals/character
                  (respec-character-devotions options)
                  (respec-character-skills options)
                  (respec-character-attributes options))

              (= mode "attributes")
              (respec-character-attributes @globals/character options)

              (= mode "skills")
              (respec-character-skills @globals/character options)

              (= mode "devotions")
              (respec-character-devotions @globals/character options)

              :else
              (throw (Throwable. (str "Unknown respec mode: " mode))))

            differences (diff @globals/character modified-character)]

        ;; Print how we're going to update the character
        (u/print-line "Updating the following fields:")
        (printer/print-map-difference differences)

        ;; Actually update the character
        (swap! globals/character (constantly modified-character))))))
