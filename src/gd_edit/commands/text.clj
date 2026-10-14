(ns gd-edit.commands.text
  "The whole build as plain text, for searching.

  Everything the character sheet shows is already assembled by
  `gd-edit.sheet.render/sheet-data` -- every item's full tooltip, every skill's
  current values, every constellation's totals. In the sheet those strings live
  in a JavaScript object and are injected when something is hovered, so they are
  never in the document: a browser's find reaches none of it.

  This writes the same strings out instead, so questions the sheet can answer
  only by hovering over thirty things in turn -- how much damage conversion is
  on this build, which item carries that weapon-pool skill, where a resistance
  comes from -- become one search.

  Two things decide whether that works, and both are easy to get wrong:

  Structure is the point. A flat list of lines answers \"do I have any damage
  conversion\" and not \"which item is it on\", which is the harder half of
  every question worth asking. Every line therefore sits under the item, skill
  or constellation it came from.

  Gear is not enough. The first question that prompted this -- where is my life
  leech resistance coming from -- has no answer in anyone's equipment: on this
  game's records it comes from devotions and from Oathkeeper's Ascension. A dump
  of item tooltips would have failed the very question it was built for, so
  skills, devotions and buffs are here too."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.app-util :as au]
            [gd-edit.globals :as globals]
            [gd-edit.item-stats :as item-stats]
            [gd-edit.sheet.render :as render]
            [gd-edit.utils :as u]
            [jansi-clj.core :refer [green red yellow]]))

;; ------------------------------------------------------------------ plumbing

(def ^:private ansi #"\u001b\[[0-9;]*m")

(defn- plain
  "One line with the terminal's colour codes taken out.

  The sheet's strings carry them because the page turns them back into styling.
  Here they would sit in the middle of a phrase and break the search this file
  exists for: `+1\\u001b[m to all skills` does not match \"+1 to all skills\"."
  [s]
  (-> (str s) (str/replace ansi "") str/trimr))

(defn- emit
  "Collect lines into `out`, indenting each by `n` levels of two spaces."
  [out n & lines]
  (doseq [l (flatten lines)]
    (let [t (plain l)]
      (swap! out conj (if (str/blank? t) "" (str (apply str (repeat n "  ")) t))))))

(defn- heading [out title]
  (swap! out conj "" (str/join (repeat 72 "=")) title (str/join (repeat 72 "="))))

;; -------------------------------------------------------------- the sections

(defn- character-section [out sheet]
  (let [c (:character sheet) s (:stats sheet)]
    (emit out 0 (str (:name c) " — level " (:level c)
                     (when (seq (:masteries c)) (str " " (str/join " / " (:masteries c))))))
    (emit out 0 (str/join " · " (remove str/blank?
                                        [(:expansion c) (:version c) (:difficulty c)])))
    (heading out "ATTRIBUTES AND COMBAT")
    (doseq [[k label] [[:physique "Physique"] [:cunning "Cunning"] [:spirit "Spirit"]
                       [:health "Health"] [:energy "Energy"] [:energyReserved "Energy reserved"]
                       [:energyUsable "Energy usable"]
                       [:offensiveAbility "Offensive Ability"]
                       [:defensiveAbility "Defensive Ability"] [:armour "Armour"]]
            :when (contains? s k)]
      (emit out 1 (format "%-22s %s" label (get s k))))
    (when-let [by (:armourBySlot s)]
      (emit out 1 "Armour by slot")
      (doseq [[slot v] by] (emit out 2 (format "%-12s %s" slot v))))))

(defn- resistance-section [out sheet]
  (heading out "RESISTANCES")
  (doseq [r (:resistances sheet)]
    (emit out 1 (format "%-26s %3s%%   cap %s%s"
                        (:label r) (:shown r) (:cap r)
                        (if (and (:over r) (> (:over r) (:shown r)))
                          (str "   (" (:over r) "% before the cap)") "")))))

(defn- item-section [out sheet]
  (heading out "EQUIPMENT")
  (doseq [it (:equipment sheet)]
    (emit out 0 "")
    (emit out 0 (str "--- " (:slot it) ": " (:name it) " ---"))
    (emit out 1 (str/join " · " (remove str/blank?
                                        [(:kind it)
                                         (when (:itemLevel it) (str "item level " (:itemLevel it)))
                                         (when (:seed it) (str "seed " (:seed it)))])))
    (emit out 1 (:primary it))
    (emit out 1 (:requires it))
    (doseq [g (:groups it)]
      (emit out 1 "")
      (emit out 1 (str/join " · " (remove str/blank? [(:heading g) (:sub g)])))
      (emit out 2 (:lines g))
      (when-let [m (:members g)]
        (doseq [x m]
          (emit out 2 (str (if (:worn x) "[worn] " "[     ] ") (:name x))))))))

(defn- skill-section [out tree]
  (heading out "SKILLS")
  (doseq [t (:trees tree)]
    (emit out 0 "")
    (emit out 0 (str "--- " (:mastery t) " (" (:level t) ") ---"))
    (doseq [n (:nodes t)
            ;; a gear bonus does nothing until a point has been spent, so a
            ;; node at 0 is not on this build however much +skill points at it
            :when (pos? (long (or (:level n) 0)))]
      (emit out 1 "")
      (emit out 1 (format "%s  %s/%s%s" (:name n) (:level n) (:max n)
                          (if (pos? (long (or (:gear n) 0)))
                            (str "  (+" (:gear n) " from gear)") "")))
      (emit out 2 (:cur n))
      (when-let [cel (:celestial n)]
        (emit out 2 (str "Bound devotion: " (:name cel)
                         (when (:chance cel) (str " — " (:chance cel) "% chance"))))
        (emit out 3 (:lines cel))))))

(defn- devotion-section [out devo]
  (heading out "DEVOTIONS")
  (doseq [c (:constellations devo)
          :when (pos? (long (or (:starsTaken c) 0)))]
    (emit out 0 "")
    (emit out 0 (format "--- %s  %s/%s stars%s ---"
                        (:name c) (:starsTaken c) (:starsTotal c)
                        (if (:complete c) "  (complete)" "")))
    (emit out 1 (:total c))
    (when (seq (:petTotal c))
      (emit out 1 "Bonus to All Pets")
      (emit out 2 (:petTotal c)))
    (doseq [s (:stars c) :when (and (:taken s) (:bound s))]
      (emit out 1 (str "Bound: " (:name s) " → " (get-in s [:bound :name]))))))

(defn- buff-section [out buffs]
  (heading out "BUFFS")
  (doseq [[k label] [[:permanent "Permanent"] [:activated "Activated"] [:triggered "Triggered"]]
          :when (seq (get buffs k))]
    (emit out 0 "")
    (emit out 0 (str "--- " label " ---"))
    (doseq [b (get buffs k)]
      (emit out 1 (str (:name b)
                       (when (:source b) (str "  —  " (:source b)))
                       (when (false? (:on b)) "  [off]")))
      ;; the trigger carries the proc chance -- "25% Chance on Attack" -- which
      ;; is the only place a weapon-pool skill's percentage appears at all
      (when (:trigger b) (emit out 2 (:trigger b)))
      (when (:boundTo b) (emit out 2 (str "bound to " (:boundTo b)))))))

;; --------------------------------------------------------------------- write

(defn text-for
  "`character` as plain text, as one string."
  [character]
  (let [d (render/sheet-data character :art? false)
        out (atom [])]
    (character-section out (:sheet d))
    (resistance-section out (:sheet d))
    (item-section out (:sheet d))
    (skill-section out (:tree d))
    (devotion-section out (:devo d))
    (buff-section out (:buffs d))
    ;; groups with no heading leave a blank behind, so squeeze runs of them
    (str (->> @out
              (partition-by str/blank?)
              (mapcat #(if (str/blank? (first %)) [""] %))
              (str/join "\n"))
         "\n")))

(defn write-text-handler
  [[_ tokens]]
  (cond
    (not (au/character-loaded?))
    (u/print-line "Don't have a character loaded yet!")

    (empty? tokens)
    (do
      (u/print-line "Usage: write character-text <path to write>")
      (u/print-line)
      (u/print-indent 1)
      (u/print-line "Examples:")
      (u/print-indent 1)
      (u/print-line "  write character-text ~/Desktop/mary.txt")
      (u/print-indent 1)
      (u/print-line "  write character-text \"C:\\Users\\You\\Desktop\\mary.txt\"")
      (u/print-line)
      (u/print-indent 1)
      (u/print-line "Everything the character sheet shows, as plain text, so it can be")
      (u/print-indent 1)
      (u/print-line "searched: gear with full tooltips, skills, devotions and buffs."))

    :else
    (let [target (io/file (u/expand-home (str/join " " tokens)))
          parent (.getParentFile (.getAbsoluteFile target))]
      (if-not (.isDirectory parent)
        (do (u/print-line (red "No such folder:"))
            (u/print-indent 1)
            (u/print-line (yellow (.getPath parent))))
        (let [text (text-for @globals/character)]
          (spit target text)
          (item-stats/warn-if-absent! "This dump")
          (u/print-line (green "Written")
                        (yellow (str (count (str/split-lines text))))
                        (green "lines to:"))
          (u/print-indent 1)
          (u/print-line (yellow (.getAbsolutePath target))))))))
