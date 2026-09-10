(ns gd-edit.commands.export
  "Write the loaded character out for use somewhere else, as a CSV or as JSON.

  The CSV is one row per fact, in a long format -- section, slot, name, field,
  value -- so a spreadsheet can filter it without anyone having to guess at a
  wide layout that changes shape depending on how many stats an item happens to
  roll.

  The JSON is aimed at other tools rather than at spreadsheets, and is
  deliberately self-contained: it carries the computed character sheet and every
  item's real rolled values, so whatever reads it needs no copy of the game
  database. That matters because the database is 173MB of Crate's data that no
  web service can ship and no player can reasonably upload, while this file is a
  few tens of kilobytes of the player's own save, resolved.

  The path is required rather than defaulted. `write character-list` defaults to
  the working directory, which is not where anyone expects: launched from Finder
  or Explorer that is the user's home rather than the folder holding the app, so
  the file lands somewhere they then have to hunt for. Asking for the path and
  echoing back where it went avoids the whole question."
  (:require [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [flatland.ordered.map :refer [ordered-map]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.app-util :as au]
            [gd-edit.character-stats :as cs]
            [gd-edit.db-utils :as dbu]
            [gd-edit.globals :as globals]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.labels :as labels]
            [gd-edit.resistances :as res]
            [gd-edit.seed-search :as ss]
            [gd-edit.self-update :as su]
            [gd-edit.utils :as u]
            [jansi-clj.core :refer [red green yellow]]))

(def ^:private columns
  ["section" "slot" "name" "field" "value" "min" "max" "at-max"])

(def ^:private equipment-slots
  ["Head" "Amulet" "Chest" "Legs" "Feet" "Hands" "Ring" "Ring"
   "Belt" "Shoulders" "Medal" "Relic"])

(defn- item-name [item]
  (str (dbu/item-name item (dbu/db-and-index))))

(defn- attached-name [path]
  (some-> (not-empty (str path)) dbu/record-by-name dbu/item-base-record-get-name))

(defn- item-rows
  "One row naming the item, then one per stat it rolled."
  [slot item]
  (when (not-empty (str (:basename item)))
    (let [record (dbu/record-by-name (:basename item))
          plan (ss/fit-plan record
                            (some-> (:modifier-name item) not-empty)
                            (some-> (:prefix-name item) not-empty)
                            (some-> (:suffix-name item) not-empty))
          ranges (when plan (ss/searchable-fields plan))
          stats (item-stats/rolled-stats item)
          round (fn [v] (when v (Math/round (double v))))]
      (concat
       [["item" slot (item-name item) "basename" (str (:basename item)) nil nil nil]
        ["item" slot (item-name item) "rarity" (str (get record "itemClassification")) nil nil nil]
        ["item" slot (item-name item) "seed" (str (:seed item)) nil nil nil]]
       (for [[k label] [[:relic-name "component"] [:augment-name "augment"]]
             :let [n (attached-name (get item k))]
             :when n]
         ["item" slot (item-name item) label n nil nil nil])
       (when-let [asc (not-empty (str (:ascended-name item)))]
         [["item" slot (item-name item) "ascended" asc nil nil nil]])
       (for [[field value] (sort-by key stats)
             :let [[lo hi] (get ranges field)]]
         ["stat" slot (item-name item)
          (or (labels/field-label field) field)
          (str (round value))
          (some-> lo round str)
          (some-> hi round str)
          (when hi (str (>= (round value) (round hi))))])))))

(defn- character-rows
  [c]
  (let [mastery? (fn [s] (= "Skill_Mastery" (get (dbu/record-by-name (:skill-name s)) "Class")))
        skill-name (fn [s]
                     (let [r (dbu/record-by-name (:skill-name s))
                           tag (some-> r dbu/skill-display-name)]
                       (or (get (dbu/localization-table) tag) tag (:skill-name s))))]
    (concat
     (for [[field label] [[:character-name "name"] [:character-level "level"]
                          [:physique "physique"] [:cunning "cunning"] [:spirit "spirit"]
                          [:health "health"] [:energy "energy"]
                          [:attribute-points "attribute-points"]
                          [:skill-points "skill-points"] [:devotion-points "devotion-points"]]
           :let [v (get c field)]]
       ["character" nil label nil
        (if (number? v) (str (Math/round (double v))) (str v)) nil nil nil])

     ;; resistances are computed rather than stored -- see gd-edit.resistances
     (for [r (res/compute c)]
       ["resistance" nil (:label r) "shown" (str (:shown r))
        nil (str (Math/round (:cap r))) (str (>= (+ (:total r) (:penalty r)) (:cap r)))])

     (mapcat item-rows equipment-slots (:equipment c))
     (mapcat (fn [ws]
               (when-not (:unused ws)
                 (mapcat item-rows ["Weapon" "Off-hand"] (:items ws))))
             (:weapon-sets c))

     ;; The engine keeps its own entries in the skill list -- Move To, Weapon
     ;; Attack, a default kick -- which are not skills anyone chose. Anything
     ;; whose name will not resolve is left out rather than printed as a path.
     (for [s (:skills c)
           :when (and (pos? (long (or (:level s) 0)))
                      (zero? (long (or (:devotion-level s) 0)))
                      (not (str/includes? (str (:skill-name s)) "/skills/default/")))
           :let [n (str (skill-name s))]
           :when (and (not (str/blank? n))
                      (not (str/starts-with? n "tag"))
                      (not (str/includes? n "/")))]
       [(if (mastery? s) "mastery" "skill") nil n "level" (str (:level s)) nil nil nil])

     ;; Each star of a constellation is its own entry and they all resolve to the
     ;; constellation's name, so listing them raw repeats "Shepherd's Crook"
     ;; three times. One row each, with the number of stars taken.
     (->> (:skills c)
          (filter #(and (:enabled %)
                        (pos? (long (or (:devotion-level %) 0)))
                        (pos? (long (or (:level %) 0)))))
          (keep (fn [s] (let [n (str (skill-name s))]
                          (when-not (or (str/blank? n) (str/includes? n "/")) n))))
          frequencies
          (sort-by key)
          (map (fn [[n stars]] ["devotion" nil n "stars" (str stars) nil nil nil]))))))

(defn export-csv-handler
  [[_ tokens]]
  (cond
    (not (au/character-loaded?))
    (u/print-line "Don't have a character loaded yet!")

    (empty? tokens)
    (do
      (u/print-line "Usage: write character-csv <path to write>")
      (u/print-line)
      (u/print-indent 1)
      (u/print-line "Examples:")
      (u/print-indent 1)
      (u/print-line "  write character-csv ~/Desktop/mary.csv")
      (u/print-indent 1)
      (u/print-line "  write character-csv \"C:\\Users\\You\\Desktop\\mary.csv\"")
      (u/print-line)
      (u/print-indent 1)
      (u/print-line "The path is required, so the file goes where you meant it to.")
      (u/print-indent 1)
      (u/print-line "Quote it if it contains spaces."))

    :else
    (let [target (io/file (u/expand-home (str/join " " tokens)))
          parent (.getParentFile (.getAbsoluteFile target))]
      (cond
        (not (.isDirectory parent))
        (do (u/print-line (red "No such folder:"))
            (u/print-indent 1)
            (u/print-line (yellow (.getPath parent))))

        :else
        (let [rows (character-rows @globals/character)]
          (with-open [w (io/writer target)]
            (csv/write-csv w (cons columns (map (fn [r] (map #(if (nil? %) "" (str %)) r)) rows))))
          (u/print-line (green "Written") (yellow (str (count rows)) ) (green "rows to:"))
          (u/print-indent 1)
          (u/print-line (yellow (.getAbsolutePath target))))))))

;; ---------------------------------------------------------------- json

(def ^:private difficulty-names ["Normal" "Elite" "Ultimate"])

(defn- skill-record-name
  "The display name of a skill, or nil when it will not resolve.

  The engine keeps its own entries in the skill list -- Move To, Weapon Attack, a
  default kick, and a potion modifier per potion in the game -- which are not
  skills anyone chose. Anything that will not resolve to a name is left out
  rather than exported as a record path."
  [skill]
  (let [r (dbu/record-by-name (:skill-name skill))
        tag (some-> r dbu/skill-display-name)
        n (str (or (get (dbu/localization-table) tag) tag))]
    (when-not (or (str/blank? n)
                  (str/starts-with? n "tag")
                  (str/includes? n "/"))
      n)))

(defn- item-json
  "One equipped item: what it is, and what it actually rolled.

  `stats` carries the real seed-applied values with the range each came from, so
  a reader can show both the number and how good the roll was without consulting
  the database."
  [slot item]
  (when (not-empty (str (:basename item)))
    (let [record (dbu/record-by-name (:basename item))
          plan (ss/fit-plan record
                            (some-> (:modifier-name item) not-empty)
                            (some-> (:prefix-name item) not-empty)
                            (some-> (:suffix-name item) not-empty))
          ranges (when plan (ss/searchable-fields plan))
          round (fn [v] (when (number? v) (long (Math/round (double v)))))]
      (cond-> (ordered-map
               "slot" slot
               "name" (item-name item)
               "basename" (str (:basename item))
               "rarity" (str (get record "itemClassification"))
               "seed" (:seed item)
               "stats" (vec (for [[field value] (sort-by key (item-stats/rolled-stats item))
                                  :let [[lo hi] (get ranges field)]]
                              (cond-> (ordered-map
                                       "field" field
                                       "label" (or (labels/field-label field) field)
                                       "value" (round value))
                                lo (assoc "min" (round lo))
                                hi (assoc "max" (round hi))
                                hi (assoc "atMax" (>= (round value) (round hi)))))))
        (attached-name (:relic-name item)) (assoc "component" (attached-name (:relic-name item)))
        (attached-name (:augment-name item)) (assoc "augment" (attached-name (:augment-name item)))
        (not-empty (str (:prefix-name item))) (assoc "prefix" (str (:prefix-name item)))
        (not-empty (str (:suffix-name item))) (assoc "suffix" (str (:suffix-name item)))
        (not-empty (str (:ascended-name item))) (assoc "ascended" (str (:ascended-name item)))
        (not-empty (str (:relic-bonus item))) (assoc "completionBonus" (str (:relic-bonus item)))
        ;; an illusion changes appearance only; it is reported, never counted
        (not-empty (str (:transmute-name item))) (assoc "illusion" (str (:transmute-name item)))))))

(defn character-json
  "The loaded character as a self-contained document.

  Everything here is resolved: the stats are the computed sheet rather than the
  inputs to it, and each item carries its rolled values rather than its record
  name alone. A reader needs no game database."
  [c]
  (let [stats (cs/compute c)
        difficulty (res/difficulty c)
        equipped (concat (map vector equipment-slots (:equipment c))
                         (mapcat (fn [i ws]
                                   (when-not (:unused ws)
                                     (map vector ["Weapon" "Off-hand"] (:items ws))))
                                 (range) (:weapon-sets c)))]
    (ordered-map
     "format" "gd-edit character"
     "formatVersion" 1
     "generator" (cond-> {"name" "gd-edit"}
                   (:version (su/get-build-info)) (assoc "version" (:version (su/get-build-info))))
     "character"
     {"name" (:character-name c)
      "level" (:character-level c)
      "difficulty" {"index" difficulty
                    "name" (get difficulty-names difficulty (str difficulty))}
      "experience" (:experience c)
      "masteries" (vec (for [s (:skills c)
                             :when (= "Skill_Mastery" (str (get (dbu/record-by-name (:skill-name s)) "Class")))
                             :let [n (skill-record-name s)]
                             :when n]
                         {"name" n "level" (:level s)}))
      "unspent" {"attribute" (:attribute-points c)
                 "skill" (:skill-points c)
                 "devotion" (:devotion-points c)}}

     ;; the computed sheet -- see gd-edit.character-stats for how each is derived
     "attributes" {"physique" (:physique stats)
                   "cunning" (:cunning stats)
                   "spirit" (:spirit stats)
                   "health" (:health stats)
                   "energy" {"max" (:energy stats)
                             "reserved" (:energy-reserved stats)
                             "usable" (:energy-usable stats)}}

     "combat" {"offensiveAbility" (:offensive-ability stats)
               "defensiveAbility" (:defensive-ability stats)
               "armour" {"rating" (:armour stats)
                         "bySlot" (:armour-by-slot stats)}}

     "resistances" (vec (for [r (res/compute c)]
                          {"name" (:label r)
                           "field" (:field r)
                           "shown" (:shown r)
                           "cap" (long (Math/round (:cap r)))
                           "beforeCap" (long (Math/round (+ (:total r) (:penalty r))))
                           "difficultyPenalty" (long (Math/round (:penalty r)))
                           "capped" (>= (+ (:total r) (:penalty r)) (:cap r))}))

     "equipment" (vec (keep (fn [[slot item]] (item-json slot item)) equipped))

     "skills" (vec (for [s (:skills c)
                         :when (and (pos? (long (or (:level s) 0)))
                                    (zero? (long (or (:devotion-level s) 0)))
                                    (not= "Skill_Mastery" (str (get (dbu/record-by-name (:skill-name s)) "Class")))
                                    (not (str/includes? (str (:skill-name s)) "/skills/default/")))
                         :let [n (skill-record-name s)]
                         :when n]
                     {"name" n "level" (:level s)}))

     ;; each star of a constellation is its own entry and they all resolve to the
     ;; constellation's name, so they are counted rather than listed
     "devotions" (vec (->> (:skills c)
                           (filter #(and (:enabled %)
                                         (pos? (long (or (:devotion-level %) 0)))
                                         (pos? (long (or (:level %) 0)))))
                           (keep skill-record-name)
                           frequencies
                           (sort-by key)
                           (map (fn [[n stars]] {"name" n "stars" stars})))))))

(defn export-json-handler
  [[_ tokens]]
  (cond
    (not (au/character-loaded?))
    (u/print-line "Don't have a character loaded yet!")

    (empty? tokens)
    (do
      (u/print-line "Usage: write character-json <path to write>")
      (u/print-line)
      (u/print-indent 1)
      (u/print-line "Examples:")
      (u/print-indent 1)
      (u/print-line "  write character-json ~/Desktop/mary.json")
      (u/print-indent 1)
      (u/print-line "  write character-json \"C:\\Users\\You\\Desktop\\mary.json\"")
      (u/print-line)
      (u/print-indent 1)
      (u/print-line "The path is required, so the file goes where you meant it to.")
      (u/print-indent 1)
      (u/print-line "Quote it if it contains spaces."))

    :else
    (let [target (io/file (u/expand-home (str/join " " tokens)))
          parent (.getParentFile (.getAbsoluteFile target))]
      (if-not (.isDirectory parent)
        (do (u/print-line (red "No such folder:"))
            (u/print-indent 1)
            (u/print-line (yellow (.getPath parent))))
        (let [document (character-json @globals/character)]
          (with-open [w (io/writer target)]
            (binding [*out* w
                      pprint/*print-right-margin* 110]
              (json/pprint document :escape-slash false)))
          (u/print-line (green "Written") (yellow (str (count (get document "equipment"))))
                        (green "equipped items and the full character sheet to:"))
          (u/print-indent 1)
          (u/print-line (yellow (.getAbsolutePath target)))
          (u/print-line)
          (u/print-indent 1)
          (u/print-line "Self-contained: it carries the computed stats and every item's real")
          (u/print-indent 1)
          (u/print-line "rolled values, so whatever reads it needs no copy of the game files."))))))
