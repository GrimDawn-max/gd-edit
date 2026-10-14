(ns gd-edit.item-skills-test
  "What block 8's item-skill records must keep true.

  These two saves were sent by a forum contributor for exactly this purpose,
  after the last of the format work found that gd-edit's own characters all
  carry an empty item-skill array -- so the struct had no coverage at all and a
  change to it would have broken in silence, the way the quest task layout did.
  `counterstrike.gdc` holds one item skill and `z20.gdc` holds two.

  They are also the only files here at block 8 version 5, which makes them worth
  keeping for a second reason: `:sub-skills` is gated at version 6, so these
  prove the gate works from the other side.

  No game installation is needed. The records are read straight out of the save,
  not resolved against the database, so this runs anywhere."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.io.gdc :as gdc]
            [gd-edit.utils :as u]))

(def ^:private fixtures
  {"counterstrike.gdc" 1
   "z20.gdc" 2})

(defn- path [n] (str "test-resources/save-files/" n))

(deftest item-skills-are-read
  (doseq [[name expected] fixtures]
    (testing name
      (let [c (gdc/load-character-file* (path name))
            skills (:item-skills c)]

        (is (= expected (count skills))
            "the whole point of this fixture is that it has item skills")

        (doseq [s skills]
          (testing (str "entry " (:skill-name s))
            ;; Every one of these was an unnamed byte blob or an unnamed string
            ;; until the layout was confirmed; they are asserted so that reading
            ;; them as something else again fails here rather than in a release.
            (is (str/starts-with? (str (:skill-name s)) "records/skills/")
                "skill-name should be a skill record")
            (is (str/starts-with? (str (:item-name s)) "records/items/")
                "item-name is the record of the item granting the skill")
            (is (int? (:item-location s)))
            ;; the range the game's own code gives. Note this is NOT an index
            ;; into :equipment -- a rune in the medal, equipment 10, reports 14.
            (is (<= 0 (:item-location s) 14)
                "item-location is a slot id in the game's own numbering")))))))

(deftest sub-skills-are-gated-by-block-version
  (doseq [[name _] fixtures]
    (testing name
      (let [c (gdc/load-character-file* (path name))
            block8 (first (filter #(= 8 (:meta-block-id %)) (:meta-block-list c)))]
        (is (= 5 (:version block8)) "these fixtures are the only block-8 version 5 saves here")
        (is (nil? (:sub-skills c))
            "below version 6 the subskill array is absent, not empty")))))

(deftest round-trips-unchanged
  (doseq [[name _] fixtures]
    (testing name
      (let [src (path name)
            dst (str (System/getProperty "java.io.tmpdir") "/item-skill-roundtrip.gdc")]
        (gdc/write-character-file (gdc/load-character-file* src) dst)
        (is (= (u/md5-file src) (u/md5-file dst))
            "reading and writing back must not change a byte")))))
