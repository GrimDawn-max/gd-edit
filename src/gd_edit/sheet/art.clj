(ns gd-edit.sheet.art
  "Every picture the sheet shows, pulled out of the game's own archives.

  The page is one file with nothing to fetch, so each image is embedded as a
  data URI. Everything here is read from the player's install at the moment the
  sheet is written -- none of it is shipped with gd-edit.

  The archives are large, so each is walked exactly once: the caller says what
  it wants by texture path and gets back a map of path to image."
  (:require [clojure.string :as str]
            [gd-edit.db-utils :as dbu]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.io.arc :as arc]
            [gd-edit.sheet.tex :as tex]))

(defn- arc-file
  "An archive under one of the game's folders, whatever case it is named in.

  gdx3 ships its resources in lower case where the base game uses title case."
  [dir kind]
  (first (filter #(.exists (java.io.File. ^String %))
                 [(str dir "/resources/" kind ".arc")
                  (str dir "/resources/" (str/lower-case kind) ".arc")])))

(defn- archives [kind]
  (keep #(arc-file (str %) kind)
        [(dirs/get-game-dir) (dirs/get-gdx1-dir) (dirs/get-gdx2-dir) (dirs/get-gdx3-dir)]))

(defn- canon
  "One key for a texture however it was spelled.

  An archive indexes a path without its leading folder -- the database says
  items/gearhead/x.tex and the archive says gearhead/x.tex -- and case varies
  between the save, the database and a material chunk."
  [n]
  (-> (str n) str/lower-case (str/replace #"^(items|creatures|ui)/" "")))

(defn- collect
  "Walk `kind`'s archives once, decoding every texture `wanted` asks for.

  `wanted` maps a canonical texture name to a cap in pixels. The result maps
  the same names to `{:uri :ow :oh :w :h}` -- the original size is kept because
  the devotion stars are plotted against the artwork's own coordinates."
  [kind wanted]
  (let [found (atom {})]
    (doseq [path (archives kind)
            :while (< (count @found) (count wanted))]
      (doseq [r (arc/load-arc-tex-file
                 path (fn [bb] (let [a (byte-array (.remaining bb))] (.get bb a) {:bytes a})))
              :let [k (canon (:recordname r))]
              :when (and (contains? wanted k) (not (contains? @found k)))]
        (when-let [img (tex/decode (:bytes r))]
          (let [scaled (tex/scale img (wanted k))]
            (swap! found assoc k {:uri (tex/->uri scaled)
                                  :ow (.getWidth img) :oh (.getHeight img)
                                  :w (.getWidth scaled) :h (.getHeight scaled)})))))
    @found))

;; What each kind of picture is drawn at. Constellation art is displayed about a
;; hundred pixels wide and unscaled runs to megabytes, so it is the one that
;; must be capped hard.
(def ^:private ITEM-CAP 96)
(def ^:private ICON-CAP 40)
(def ^:private ART-CAP 150)
(def ^:private RES-CAP 24)

(defn- tex-of [record field]
  (some-> record (get field) not-empty str))

(defn item-art
  "The inventory icon for each equipped item, and the badge for its component.

  Both come out of Items.arc, so they are gathered together in one walk."
  [character]
  (let [items (->> (concat (:equipment character)
                           (get-in character [:weapon-sets 0 :items]))
                   (filter #(not-empty (str (:basename %)))))
        name-of (fn [it] (str (dbu/item-name it (dbu/db-and-index))))
        ;; a component names its finished art relicBitmap; shardBitmap is the
        ;; unfinished shard, and bitmapName is not a field these carry
        want (fn [it fields rec]
               (when-let [b (some #(tex-of rec %) fields)] [(canon b) (name-of it)]))
        ;; a relic names its art artifactBitmap; everything else uses bitmap
        icon-of (keep #(want % ["bitmap" "artifactBitmap"]
                             (dbu/record-by-name (:basename %))) items)
        comp-of (keep #(want % ["relicBitmap"]
                             (some-> (:relic-name %) not-empty str dbu/record-by-name))
                      items)
        ;; one texture may dress several items -- two identical rings, or two
        ;; pieces carrying the same component -- so a name maps to many items
        by-tex (fn [pairs] (reduce (fn [m [t n]] (update m t (fnil conj #{}) n)) {} pairs))
        icon-tex (by-tex icon-of)
        comp-tex (by-tex comp-of)
        got (collect "Items" (merge (zipmap (keys comp-tex) (repeat ITEM-CAP))
                                    (zipmap (keys icon-tex) (repeat ITEM-CAP))))
        spread (fn [tex-map] (into {} (for [[t names] tex-map
                                            :let [img (got t)]
                                            :when img
                                            n names]
                                        [n (:uri img)])))]
    {:icons (spread icon-tex)
     :compicons (spread comp-tex)}))

(defn- skill-textures
  "Every skill, buff and bound-proc icon the page will draw, by texture path."
  [tree devo buffs]
  (into #{}
        (concat
         (for [t (:trees tree) n (:nodes t) :when (:tex n)] (canon (:tex n)))
         (for [k (:constellations devo) s (:stars k)
               p [(:tex s) (get-in s [:bound :tex])] :when p] (canon p))
         (for [[_ items] buffs b items :when (:tex b)] (canon (:tex b))))))

(defn tree-art
  "Skill icons, constellation artwork, and the icons on the buff list.

  A constellation's background is reached the long way round -- the
  constellation record names it -- and is keyed by the name the page knows it
  by rather than by its texture, since that is what the renderer asks for."
  [tree devo buffs]
  (let [mine (set (map :name (:constellations devo)))
        art (into {} (for [r (dbu/db)
                           :when (re-find #"/constellations/constellation\d+\.dbr$"
                                          (str (:recordname r)))
                           :let [nm (some-> (get r "constellationDisplayTag") not-empty str)
                                 bg (some-> (get r "constellationBackground") not-empty str
                                            dbu/record-by-name)
                                 bmp (tex-of bg "bitmapName")]
                           :when (and nm bmp (contains? mine nm))]
                       [(canon bmp) (str "constellation:" nm)]))
        icons (skill-textures tree devo buffs)
        ;; an "up" icon has a "down" twin; either may be the one in the archive
        with-twins (into icons (for [p icons :when (str/includes? p "up.tex")]
                                 (str/replace p "up.tex" "down.tex")))
        wanted (merge (zipmap with-twins (repeat ICON-CAP))
                      (zipmap (keys art) (repeat ART-CAP)))
        got (collect "UI" wanted)]
    (into {} (concat
              ;; the icons keep their own path as the key
              (for [p icons :let [img (or (got p)
                                          (got (str/replace p "up.tex" "down.tex")))]
                    :when img]
                [p img])
              ;; the artwork answers to the constellation's name
              (for [[t nm] art :let [img (got t)] :when img] [nm img])))))

;; Which icon belongs to which resistance, from the grid the game's own UI
;; records lay out: row one runs 01 03 02 04 05, row two 06 07 08 10 09.
(def ^:private resistance-icons
  {"defensiveFire" 1, "defensiveLightning" 2, "defensiveCold" 3,
   "defensivePoison" 4, "defensivePierce" 5, "defensiveBleeding" 6,
   "defensiveLife" 7, "defensiveAether" 8, "defensivePhysical" 9,
   "defensiveChaos" 10})

(defn resistance-art []
  (let [want (into {} (for [[_ n] resistance-icons]
                        [(canon (format "character/infotabs/resistance%02d.tex" n)) RES-CAP]))
        got (collect "UI" want)]
    (into {} (for [[field n] resistance-icons
                   :let [img (got (canon (format "character/infotabs/resistance%02d.tex" n)))]
                   :when img]
               [field (:uri img)]))))
