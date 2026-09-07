(ns gd-edit.commands.transmog
  "Listing the illusions unlocked on this account.

  Illusions -- transmogs elsewhere -- change an item's appearance without
  touching its stats. They come from the Loyalist packs and from drops, and the
  set a player is entitled to lives in transmutes.gst, account-wide rather than
  per character.

  This only reads. Which illusion is applied to an item is a separate thing,
  stored in that item's :transmute-name, and `set` already writes it. Choosing
  one is far better done in game, where the interface previews the result;
  what the game gives you no way to see is the collection itself, which is what
  this command is for."
  (:require [gd-edit.db-utils :as dbu]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.io.stash :as stash]
            [gd-edit.utils :as u]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jansi-clj.core :refer [yellow red green]]))

(defn- transmutes-file
  "The illusion collection, which sits beside the transfer stash.

  Both live in the *parent* of the save directories -- the save root -- not in
  the per-mode folders the search list returns, so take the parent the same way
  get-transfer-stash does. Hardcore keeps its own copy under .gsh."
  []
  (->> (dirs/get-save-dir-search-list)
       (map #(.getParentFile (io/file %)))
       (mapcat (fn [d] [(io/file d "transmutes.gst") (io/file d "transmutes.gsh")]))
       (filter #(.exists %))
       first))

(defn- describe
  "An illusion record as a name, falling back to the record path.

  Many of these are ordinary items being worn as a costume, so the item's own
  display name is the useful label."
  [recordname]
  (or (some-> (dbu/record-by-name recordname) dbu/item-base-record-get-name)
      recordname))

(defn- slot-of
  "A readable slot for an illusion, taken from where its record lives."
  [recordname]
  (condp #(str/includes? %2 %1) recordname
    "/gearhead/"        "Head"
    "/geartorso/"       "Chest"
    "/gearlegs/"        "Legs"
    "/gearfeet/"        "Feet"
    "/gearhands/"       "Hands"
    "/gearshoulders/"   "Shoulders"
    "/gearaccessories/" "Accessory"
    "/gearweapons/"     "Weapon"
    "Other"))

(defn transmog-list-handler
  [[_ tokens]]
  (if-let [f (transmutes-file)]
    (if-let [records (stash/load-illusions (.getPath f))]
      (let [filter-text (some-> (seq tokens) (->> (str/join " ")) str/trim str/lower-case not-empty)
            rows (->> records
                      (map (fn [r] {:record r :name (describe r) :slot (slot-of r)}))
                      (filter (fn [{:keys [name record]}]
                                (or (nil? filter-text)
                                    (str/includes? (str/lower-case (str name)) filter-text)
                                    (str/includes? (str/lower-case record) filter-text))))
                      (sort-by (juxt :slot :name)))]
        (if (empty? rows)
          (u/print-line "No illusions match that.")
          (do
            ;; partition-by yields the groups themselves, not [key group] pairs
            (doseq [group (partition-by :slot rows)]
              (u/print-line)
              (u/print-line (yellow (:slot (first group))))
              (doseq [row group]
                (u/print-indent 1)
                (u/print-line (format "%-42s %s" (:name row) (:record row)))))
            (u/print-line)
            (u/print-line (str (count rows)
                               (if filter-text " matching illusions" " illusions unlocked")
                               (when filter-text (format " of %d" (count records))))))))
      (do
        (u/print-line (red "Could not read the illusion collection."))
        (u/print-line "The file is there but not in a layout gd-edit understands:")
        (u/print-line "   " (.getPath f))))
    (do
      (u/print-line "No transmutes.gst found in the save directory.")
      (u/print-line "That file holds the illusions unlocked on the account. It appears once")
      (u/print-line "the game has written it, and only if illusions are owned at all --")
      (u/print-line "they come from the Loyalist packs and from drops."))))
