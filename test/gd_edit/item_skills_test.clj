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

(deftest a-populated-subskill-array-survives-a-write
  ;; Nobody has a save with a subskill in it -- not here, and not the contributor
  ;; who found the field -- so the writer for that array had never run with
  ;; anything in it. The game stores a shapeshift form's granted skills in the
  ;; ordinary skill list and leaves this array empty, which is why it looks
  ;; vestigial. Vestigial or not, reading the count and ignoring it was wrong,
  ;; and this is the only way to exercise the fix until a real one appears.
  (let [src "test-resources/save-files/NamedTabs.gdc"   ; the one fixture at block 8 version 8
        dst (str (System/getProperty "java.io.tmpdir") "/subskill-written.gdc")
        entries [{:skill-name "records/skills/playerclass10/wereraven1_skill01_icicles.dbr"
                  :autocast-skill-name "" :autocast-controller-name ""
                  :parent-skill-name "records/skills/playerclass10/wereraven1.dbr"}
                 {:skill-name "records/skills/playerclass10/wereraven1_skill02_icering.dbr"
                  :autocast-skill-name "" :autocast-controller-name ""
                  :parent-skill-name "records/skills/playerclass10/wereraven1.dbr"}]
        c (gdc/load-character-file* src)]

    (is (= [] (:sub-skills c)) "the fixture starts with an empty array, not a missing one")

    (gdc/write-character-file
     (-> c
         (assoc :sub-skills entries)
         (update :meta-block-list
                 (fn [bs] (mapv #(if (= 8 (:meta-block-id %)) (assoc % :sub-skills entries) %) bs))))
     dst)

    (let [back (gdc/load-character-file* dst)]
      (is (= 2 (count (:sub-skills back))))
      (is (= entries (mapv #(select-keys % [:skill-name :autocast-skill-name
                                            :autocast-controller-name :parent-skill-name])
                           (:sub-skills back)))
          "every one of the four strings must come back as it went in")
      (testing "and the rest of the character is undisturbed"
        (is (= (count (:skills c)) (count (:skills back))))
        (is (= (count (:equipment c)) (count (:equipment back))))
        (is (= (:character-name c) (:character-name back)))))))

