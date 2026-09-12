;; Values that cannot be in the range printed beside them.
;;
;;     GD_GAME_DIR="/path/to/Grim Dawn" GD_SAVE_DIR="/path/to/save/main" \
;;       clojure -M:test -i dev/range-sanity.clj
;;
;; Every summary line of the form "<value> ... [low-high]" states two things that
;; have to agree. When they do not, one of them is wrong and the item says so
;; itself -- no game, no screenshots, no reading required.
;;
;; This is how the Health line on a ring was caught: 551 beside [192-288],
;; because the value counted the prefix's contribution and the range measured
;; the base record on its own.
(require '[gd-edit.db-utils :as dbu] '[gd-edit.item-summary :as isum]
         '[gd-edit.io.gdc :as gdc] '[clojure.string :as str])

(def game-dir
  (or (System/getenv "GD_GAME_DIR")
      (throw (ex-info "Set GD_GAME_DIR to your Grim Dawn folder." {}))))
(def save-dir
  (or (System/getenv "GD_SAVE_DIR")
      (str (System/getProperty "user.home")
           "/Documents/My Games/Grim Dawn/save/main")))

(defn- plain [s]
  (str/replace (str s) (re-pattern (str (char 27) "\\[[0-9;]*m")) ""))

;; "+551 Health [483-657]" and "8-17 Chaos Damage [5-8]" both state a value and a
;; range; the second stating two numbers for the value, of which the first is the
;; one the range is fitted to.
(def line-pattern
  #"^\s*([-+]?[0-9]+(?:\.[0-9]+)?)(?:-[0-9.]+)?%?\s+[^\[]*\[([0-9.]+)-([0-9.]+)\]")

(let [load-loc (requiring-resolve 'gd-edit.io.arc/load-localization-table)
      load-db  (requiring-resolve 'gd-edit.io.arz/load-game-db)
      build-ix (requiring-resolve 'gd-edit.app-util/build-db-index)
      loc (reduce merge {} (map #(load-loc (str game-dir % "/resources/Text_EN.arc")) ["" "/gdx1" "/gdx2" "/gdx3"]))
      db (mapcat #(load-db (str game-dir %) loc) ["/database/database.arz" "/gdx1/database/GDX1.arz" "/gdx2/database/GDX2.arz" "/gdx3/database/GDX3.arz"])
      ix (build-ix db)]
  (swap! (var-get (requiring-resolve 'gd-edit.globals/settings)) assoc :game-dir game-dir)
  (intern 'gd-edit.globals 'localization-table (future loc))
  (intern 'gd-edit.globals 'db (future db))
  (intern 'gd-edit.globals 'db-index (future ix))
  (intern 'gd-edit.globals 'db-and-index (future {:db db :index ix}))

  (let [checked (atom 0) bad (atom [])]
    (doseq [f (sort (.listFiles (java.io.File. ^String save-dir)))
            :let [g (java.io.File. f "player.gdc")]
            :when (.exists g)]
      (let [c (gdc/load-character-file (str g))]
        (doseq [it (concat (:equipment c) (get-in c [:weapon-sets 0 :items]))
                :when (not-empty (str (:basename it)))
                :let [nm (str (dbu/item-name it (dbu/db-and-index)))]
                l (flatten (isum/item-summary it))
                :let [s (plain (str l))
                      ;; "20% Chance of 4644 Acid Retaliation [3200-4800]" states
                      ;; the chance first and the value the range describes second
                      s (str/replace s #"^\s*[0-9.]+% Chance (of|to be|on|when) " "")
                      m (re-find line-pattern s)]
                :when m]
          (swap! checked inc)
          (let [[_ v lo hi] m
                v (Double/parseDouble v) lo (Double/parseDouble lo) hi (Double/parseDouble hi)]
            ;; A reduction is printed with its sign and its range without one:
            ;; "-11% Skill Energy Cost [9-11]" is 11 out of a possible 9 to 11,
            ;; and reads correctly enough. Compare the magnitude there.
            (let [v (if (and (neg? v) (pos? lo) (pos? hi)) (- v) v)]
              ;; a hair's tolerance: a rolled value is rounded for display and
              ;; its range is not, so a value can sit a whisker outside honestly
              (when (or (< v (- lo 1.0)) (> v (+ hi 1.0)))
                (swap! bad conj [(.getName f) nm (str/trim s)])))))))
    (println (format "%d lines carry both a value and a range" @checked))
    (println (format "%d of them state a value the range cannot hold" (count @bad)))
    (println)
    (doseq [[who nm s] (take 40 @bad)]
      (println (format "   %-13s %-40s %s" who (subs nm 0 (min 40 (count nm))) s)))))
(shutdown-agents)
