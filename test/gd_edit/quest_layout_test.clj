(ns gd-edit.quest-layout-test
  "What `TaskInnerBlock` must keep true.

  The byte Fangs of Asterkarn added to each task sits after the conditions, not
  before them. Putting it before consumes exactly the same bytes -- 4 + 1 + 1 +
  4N either way -- so a quest file still parses, still round-trips, and never
  complains. gd-edit had it the wrong way round from the day FoA support landed
  until 2026-10, and nothing caught it, because the only visible symptom was in
  values nothing read.

  So the test is on the values rather than on whether it parses. Grim Dawn's
  condition counters are small -- 0 and 1 almost always. Read one byte out of
  step they become uniformly distributed 32-bit noise, which is what the wrong
  layout produced for every one of them. That difference is the check.

  The second check is semantic: a task's point reward is complete exactly when
  the task is. On the file this was developed against, that held for all 1764
  tasks with no exceptions in either direction.

  Needs a Grim Dawn save, so it skips loudly without one."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [gd-edit.io.gdd :as gdd]
            [gd-edit.utils :as u]))

(def save-dir (System/getenv "GD_EDIT_TEST_SAVEDIR"))

(defn- quest-files []
  (when save-dir
    (->> (io/file save-dir) file-seq (filter #(= "quests.gdd" (.getName ^java.io.File %))) sort)))

(def available? (delay (boolean (seq (quest-files)))))

(defn- tasks-in [f]
  (let [found (atom [])]
    (walk/postwalk (fn [x] (when (and (map? x) (contains? x :conditions)) (swap! found conj x)) x)
                   (gdd/load-quest-file (.getPath ^java.io.File f)))
    @found))

(defn- skip-note []
  (println "  SKIPPED quest-layout tests -- set GD_EDIT_TEST_SAVEDIR to a save folder holding quests.gdd"))

(deftest task-layout
  (if-not @available?
    (skip-note)
    (let [files (take 3 (quest-files))
          tasks (mapcat tasks-in files)]

      (is (seq tasks) "no tasks read at all")

      (testing "conditions are counters, not noise -- the whole signature of the alignment"
        (let [values (mapcat :conditions tasks)]
          (when (seq values)
            (is (every? #(< (bit-and (long %) 0xffffffff) 16777216) values)
                "a condition at or above 2^24 means the array is being read out of step")
            ;; by far the most common values in any real file
            (is (> (/ (count (filter #{0 1} values)) (double (count values))) 0.5)
                "most conditions should be 0 or 1"))))

      (testing "the two flags are flags"
        (is (every? #{0 1} (map :was-in-progress tasks)))
        (is (every? #{0 1} (map :point-reward-complete tasks))))

      (testing "a task's point reward is complete exactly when the task is"
        (let [complete (filter #(= 3 (:state %)) tasks)
              rewarded (filter #(= 1 (:point-reward-complete %)) tasks)]
          (is (= (count complete) (count rewarded)
                 (count (filter #(and (= 3 (:state %)) (= 1 (:point-reward-complete %))) tasks)))
              "state 3 and the reward flag should name the same set of tasks"))))))

(deftest round-trips-unchanged
  (if-not @available?
    (skip-note)
    (doseq [f (take 3 (quest-files))]
      (let [dst (str (System/getProperty "java.io.tmpdir") "/quest-roundtrip.gdd")]
        (gdd/write-quest-file (gdd/load-quest-file (.getPath ^java.io.File f)) dst)
        (is (= (u/md5-file (.getPath ^java.io.File f)) (u/md5-file dst))
            (str "writing " (.getPath ^java.io.File f) " back changed it"))))))
