(ns gd-edit.commands.resists
  "Show the loaded character's resistances as the game shows them."
  (:require [clojure.string :as str]
            [gd-edit.app-util :as au]
            [gd-edit.globals :as globals]
            [gd-edit.resistances :as res]
            [gd-edit.utils :as u]
            [jansi-clj.core :refer [red green yellow]]))

(def ^:private difficulty-names ["Normal" "Elite" "Ultimate"])

(defn- source-label
  [k]
  (case k
    :gear "items"
    :attachments "components/augments"
    :devotions "devotions"
    :auras "active auras"
    (name k)))

(defn- breakdown
  "Where a resistance came from, as \"items 56 + devotions 8\"."
  [parts]
  (->> [:gear :attachments :devotions :auras]
       (keep (fn [k] (when-let [v (get parts k)]
                       (format "%s %s" (source-label k) (u/maybe-int v)))))
       (str/join " + ")))

(defn resists-handler
  [[_ tokens]]
  (if-not (au/character-loaded?)
    (u/print-line "Don't have a character loaded yet!")
    (let [character @globals/character
          rows (res/compute character)
          d (res/difficulty character)
          verbose? (some #{"all" "full"} (map str/lower-case tokens))]
      (u/print-line)
      (u/print-line (format "Resistances on %s difficulty"
                            (get difficulty-names d (str d))))
      (u/print-line)
      (doseq [{:keys [label shown cap total penalty parts]} rows]
        (let [capped? (>= (+ total penalty) cap)
              value (format "%3d%%" shown)]
          (u/print-indent 1)
          (u/print-line
           (format "%-16s %s%s"
                   label
                   (if capped? (yellow value) (green value))
                   (cond
                     verbose?
                     (format "   %s%s%s"
                             (breakdown parts)
                             (if (zero? penalty) "" (format ", %s penalty" (u/maybe-int penalty)))
                             (if capped? (format ", capped at %s" (u/maybe-int cap)) ""))

                     capped?
                     (format "   capped -- %s before the cap" (u/maybe-int (+ total penalty)))

                     :else "")))))
      (u/print-line)
      (when-not verbose?
        (u/print-indent 1)
        (u/print-line "\"resists all\" shows where each figure comes from."))
      (u/print-indent 1)
      (u/print-line "Computed from your gear, devotions and active auras -- the game does")
      (u/print-indent 1)
      (u/print-line "not store these. If one disagrees with the character sheet, that is")
      (u/print-indent 1)
      (u/print-line "worth reporting.")
      (u/print-line))))
