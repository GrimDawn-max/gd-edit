(ns gd-edit.commands.export
  "Write the loaded character out as a CSV, for use somewhere else.

  One row per fact, in a long format -- section, slot, name, field, value -- so a
  spreadsheet can filter it without anyone having to guess at a wide layout that
  changes shape depending on how many stats an item happens to roll.

  The path is required rather than defaulted. `write character-list` defaults to
  the working directory, which is not where anyone expects: launched from Finder
  or Explorer that is the user's home rather than the folder holding the app, so
  the file lands somewhere they then have to hunt for. Asking for the path and
  echoing back where it went avoids the whole question."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.app-util :as au]
            [gd-edit.db-utils :as dbu]
            [gd-edit.globals :as globals]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.labels :as labels]
            [gd-edit.resistances :as res]
            [gd-edit.seed-search :as ss]
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
