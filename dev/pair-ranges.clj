;; Spreads shown with only half their range.
;;
;;     GD_GAME_DIR="/path/to/Grim Dawn" clojure -M:test -i dev/pair-ranges.clj
;;
;; A flat damage line states two numbers -- "93-119 Cold Damage" -- and both
;; ends rolled, from ranges of their own. A single bracket beside it describes
;; the first only, and reads as the range of the whole spread.
;;
;; Two questions here. Which stats are shaped that way at all, across every item
;; in the game; and does any line still print a spread with one bracket.
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

  ;; every Min/Max pair the game's items actually use
  (let [families (atom {})]
    (doseq [r (dbu/db)
            :when (re-find #"^(Item|Armor|Weapon)" (str (get r "Class")))
            [k v] r
            :when (and (string? k) (str/ends-with? k "Min") (number? v))
            :let [stem (subs k 0 (- (count k) 3))]
            :when (number? (get r (str stem "Max")))]
      (swap! families update stem (fnil inc 0)))
    (println (format "stats stated as a Min/Max pair, across every item in the game (%d kinds):"
                     (count @families)))
    (doseq [[stem n] (take 24 (sort-by (comp - val) @families))]
      (println (format "   %-34s on %5d items" stem n))))

  ;; and whether any line still shows a spread with a single bracket
  (println)
  (let [pairs (atom 0) half (atom [])]
    (doseq [f (sort (.listFiles (java.io.File. ^String save-dir)))
            :let [g (java.io.File. f "player.gdc")]
            :when (.exists g)]
      (let [c (gdc/load-character-file (str g))]
        (doseq [it (concat (:equipment c) (get-in c [:weapon-sets 0 :items]))
                :when (not-empty (str (:basename it)))
                :let [nm (str (dbu/item-name it (dbu/db-and-index)))]
                l (flatten (isum/item-summary it))
                :let [s (str/trim (plain (str l)))]
                ;; a line leading with "A-B" and carrying a bracket
                :when (re-find #"^[-+]?[0-9.]+-[0-9.]+.*\[" s)]
          (swap! pairs inc)
          (when-not (re-find #"\[\(.*\)-\(.*\)\]" s)
            (swap! half conj [(.getName f) nm s])))))
    (println (format "spread lines carrying a range: %d" @pairs))
    (println (format "  of those, showing only one half: %d" (count @half)))
    (doseq [[who nm s] (take 20 @half)]
      (println (format "     %-13s %-34s %s" who (subs nm 0 (min 34 (count nm))) s)))))
(shutdown-agents)
