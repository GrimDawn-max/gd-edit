(ns gd-edit.write-safety-test
  "That writing a character cannot lose it.

  Someone made an item with `find-seed` on 0.2.581, typed `write`, and ended up
  with no save file at all. Two separate faults lined up:

  The item was missing a field the writer needs. Three item fields had just been
  renamed from the int32 zeros they had been read as for years to the strings
  they really are, and `blank-item` still set the old key -- so every newly made
  item failed to serialise.

  And the backup is taken by *renaming* the original out of the way. So the
  order was: move the save aside, then fail. The character survived only because
  a .bak happened to exist.

  Both are covered here. The first by making an item the way the commands do and
  writing a character that holds it; the second by the two properties that make
  the write safe whatever else breaks -- serialisation happens before anything
  on disk is touched, and the file is replaced atomically."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [gd-edit.commands.item :as item]
            [gd-edit.io.gdc :as gdc]
            [gd-edit.utils :as u]))

(def ^:private fixture "test-resources/save-files/NamedTabs.gdc")

(defn- temp [name]
  (str (System/getProperty "java.io.tmpdir") "/" name))

(deftest a-newly-made-item-can-be-written
  (let [blank (item/blank-item)]

    (testing "blank-item carries the fields the writer needs, by name"
      ;; named individually rather than by a round-trip alone, so a rename that
      ;; misses this map fails here with something that says which field
      (doseq [k [:augment-bonus :ascended-name :ascended-bonus :relic-bonus]]
        (is (contains? blank k) (str "blank-item is missing " k)))
      (is (not (contains? blank :unknown))
          ":unknown was the old name for :augment-bonus and must not come back"))

    (testing "and a character holding one writes and reads back"
      (let [c (gdc/load-character-file* fixture)
            made (assoc blank
                        :basename "records/items/gearaccessories/waist/c018_waist.dbr"
                        :seed 1770170446 :X 0 :Y 0)
            before (count (get-in c [:inventory-sacks 0 :inventory-items]))
            dst (temp "made-item.gdc")]
        (gdc/write-character-file
         (update-in c [:inventory-sacks 0 :inventory-items] conj made) dst)
        (let [back (gdc/load-character-file* dst)]
          (is (= (inc before) (count (get-in back [:inventory-sacks 0 :inventory-items])))
              "the made item should be there when the file is read again"))))))

(deftest serialising-happens-before-anything-is-touched
  ;; The guarantee that makes the backup-by-rename survivable: if a character
  ;; cannot be serialised, that is known before any file is moved or opened.
  (let [c (gdc/load-character-file* fixture)
        broken (update-in c [:inventory-sacks 0 :inventory-items]
                          conj (-> (item/blank-item)
                                   (assoc :basename "records/items/gearaccessories/waist/c018_waist.dbr"
                                          :X 0 :Y 0)
                                   (dissoc :augment-bonus)))]
    (is (thrown? Throwable (gdc/character->bytes broken))
        "a character the writer cannot serialise must fail while still in memory")))

(deftest the-file-is-replaced-atomically
  (let [src fixture
        dst (temp "atomic-target.gdc")]
    ;; start with something else at the destination
    (io/copy (io/file src) (io/file dst))
    (let [original (u/md5-file dst)
          c (gdc/load-character-file* fixture)]

      (gdc/write-to-file (gdc/character->bytes c) dst)

      (testing "the destination holds whole, readable contents afterwards"
        (is (= original (u/md5-file dst))
            "writing the same character back should reproduce it byte for byte"))

      (testing "and no temp file is left behind"
        (is (not (.exists (io/file (str dst ".tmp")))))))))

(deftest resource-paths-keep-their-first-letter
  "make-character extracts a blank character out of the jar and then reads it
  back. The path of each resource arrives with a leading separator from some
  classpath entries and without one from others, and the code dropped the first
  character unconditionally -- so from one jar \"player.gdc\" was written as
  \"layer.gdc\" and make-character failed on a file it had just created.

  It worked from source and from the previous release's jar, which is why it
  shipped. The handling is a pure function so both forms can be checked here."
  (is (= "player.gdc" (u/resource-relative-path "/player.gdc")))
  (is (= "player.gdc" (u/resource-relative-path "player.gdc")))
  (is (= "levels_world001.map/Normal/map.dat"
         (u/resource-relative-path "/levels_world001.map/Normal/map.dat")))
  (is (= "levels_world001.map/Normal/map.dat"
         (u/resource-relative-path "levels_world001.map/Normal/map.dat")))
  (is (= "" (u/resource-relative-path "/")))
  (is (= "" (u/resource-relative-path ""))))
