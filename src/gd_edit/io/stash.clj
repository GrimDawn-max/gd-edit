(ns gd-edit.io.stash
  (:require [gd-edit.structure :as s]
            [gd-edit.utils :as u]
            [gd-edit.io.gdc :as gdc]
            [gd-edit.game-dirs :as dirs])
  (:import  [java.nio ByteBuffer ByteOrder]))

(def ^:dynamic *debug* false)

(defn read-int-no-update
  [bb context]
  (gdc/decrypt-int (.getInt bb) (:enc-state @context)))

(defn write-int-no-update
  [bb data context]
  (.putInt bb (gdc/encrypt-int data (:enc-state @context))))

(defn struct-block
  [block-specs]
  (with-meta block-specs
    {:struct/type :block}))

(defmethod s/read-spec :block
  [spec bb _ context]
  (gdc/read-block bb context spec))

(defmethod s/write-spec :block
  [spec bb data context]
  (gdc/write-block bb data context spec))

(def foa-stash-version
  "Stash block version introduced by the Fangs of Asterkarn expansion (gdx3).

  Files written before it are version 5. Version 11 appended a 5 field trailer
  to each sack; the matching item changes live in `gdc/Item`, gated on
  `gdc/item-version-foa`."
  11)

;; The version of the stash block lives in block 18, but the fields it gates
;; live further down, nested inside the sacks and their items. Neither
;; `after-block-version` (which reads the version out of the struct currently
;; being read) nor the anchor stack can see across that nesting, so the version
;; is recorded in the encryption context instead: that context atom is threaded
;; through every read and write, at any depth.

(def StashVersion
  (with-meta '(:stash-version)
    {:struct/type :stash-version}))

(defmethod s/read-spec :stash-version
  [_ bb _ context]
  (let [version (gdc/read-int! bb context)]
    (swap! context assoc :stash-version version)
    ;; the items nested below are governed by this same version
    (gdc/record-item-version! context version)
    version))

(defmethod s/write-spec :stash-version
  [_ bb data context]
  (swap! context assoc :stash-version data)
  (gdc/record-item-version! context data)
  (gdc/write-int! bb data context))

(defn after-stash-version
  "Only read/write `spec` when the stash file is at version `version` or later."
  [version spec]
  (s/conditional
   (fn [_data context]
     (when (>= (get @context :stash-version 0) version)
       spec))))

(def TransferStashItem
  ;; The 4 fields Fangs of Asterkarn added live inside `gdc/Item` itself, which
  ;; is why an unpatched editor reads X/Y as zero for every stash item.
  (into gdc/Item
        (s/struct-def
         :X :float
         :Y :float)))

(def InventorySack
  (s/struct-def
   :width           :int32
   :height          :int32
   :inventory-items (s/array TransferStashItem)
   ;; Fangs of Asterkarn appended this trailer to every sack. Zero in every
   ;; file observed so far; purpose unknown.
   :v11-sack-unk1   (after-stash-version foa-stash-version :int32z)
   :v11-sack-unk2   (after-stash-version foa-stash-version :int32z)
   :v11-sack-unk3   (after-stash-version foa-stash-version :int32z)
   :v11-sack-unk4   (after-stash-version foa-stash-version :int32z)
   :v11-sack-unk5   (after-stash-version foa-stash-version :int32z)))

(def Block18
  (s/struct-def
   :version   StashVersion
   :unknown   :int32-
   :mod       (s/string :ascii)
   :expansion-status :byte
   :stash     (s/array
               (struct-block {0 InventorySack}))))

;; The transfer stash file seem to have a
(defn make-enc-context
  [& rest]
  (let [context (apply gdc/make-enc-context rest)]
    (swap! context update-in [:rw-fns] assoc
           :int32- [:int32- 4 read-int-no-update write-int-no-update])
    context))

(defn load-stash-file
  [filepath]

  (let [bb ^ByteBuffer (u/file-contents filepath)
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)

        seed (bit-xor (Integer/toUnsignedLong (.getInt bb)) 1431655765)
        enc-table (gdc/generate-encryption-table seed)
        enc-context (make-enc-context seed enc-table)

        magic-number (gdc/read-int! bb enc-context)]
    (when (not= magic-number 2)
      (throw (Throwable. "I don't understand this stash format!")))

    (merge
      (gdc/read-block bb enc-context {18 Block18})
      {:meta-stash-seed seed
       :meta-stash-loaded-from filepath})))

(defn write-stash-file
  [stash savepath]
  (let [bb (ByteBuffer/allocate (* 10 1024 1024))
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)

        seed (:meta-stash-seed stash)
        enc-table (gdc/generate-encryption-table seed)
        enc-context (make-enc-context seed enc-table {:direction :write})]

    ;; enc key. The seed is held as an unsigned 32 bit quantity in a long, so
    ;; mask back down to 32 bits before writing -- otherwise any seed with the
    ;; high bit set is out of range for .putInt.
    (.putInt bb (.intValue (bit-and 0x00000000ffffffff
                                    (bit-xor seed 1431655765))))
    (gdc/write-int! bb 2 enc-context)       ;; magic number

    (gdc/write-block bb stash enc-context {18 Block18})

    ;; Dump everything to file
    (.flip bb)
    (gdc/write-to-file bb savepath)))


(comment
  (load-stash-file (dirs/get-transfer-stash))
  (with-bindings {#'gd-edit.io.gdc/*debug* true
                  #'gd-edit.structure/*debug* true}
    (load-stash-file "/Users/Odie/tmp/gd-stash.gst")
    )

  (load-stash-file "/Volumes/Untitled/Users/Odie/Documents/my games/Grim Dawn/save/transfer.gst")

  (let [stash (load-stash-file "/Volumes/Untitled/Users/Odie/Documents/my games/Grim Dawn/save/transfer.gst")]
    (write-stash-file stash "/Users/Odie/tmp/out.gst"))

  (load-stash-file "/Users/Odie/tmp/out.gst")

  )
