(ns gd-edit.stash-tabs-test
  "Stash tabs that have been given a name.

  Grim Dawn lets a stash tab be renamed and given an icon and colour, by
  right-clicking it. Fangs of Asterkarn appended that to the save: four ints
  for the icon and colour, then the name as a UTF-16LE string.

  The name's length prefix was read as a fifth int for a long time and nobody
  noticed, because every tab anyone had was unnamed -- and a zero length with
  no characters behind it is byte-identical to an int zero. The first named tab
  to arrive left the block short by twice the name's length, and gd-edit refused
  the whole file.

  It then happened twice, a release apart, because the transfer stash and the
  character's own stash carry the same trailer and only one of them was looked
  at. The fixtures here are a character file and a transfer stash that each
  carry named tabs, which is the thing no sample had before:

    NamedTabs.gdc           personal stash, tabs 0 and 1 named
    NamedTabs-transfer.gst  transfer stash, tabs 0 and 1 named

  Either file refuses to load on a build without the fix, so these tests fail
  loudly rather than subtly if the field is ever read as an int again."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [gd-edit.io.gdc :as gdc]
            [gd-edit.io.stash :as stash])
  (:import (java.nio.file Files)
           (java.security MessageDigest)))

(def ^:private character "test-resources/save-files/NamedTabs.gdc")
(def ^:private transfer  "test-resources/save-files/NamedTabs-transfer.gst")

(defn- sha [path]
  (vec (.digest (MessageDigest/getInstance "SHA-256")
                (Files/readAllBytes (.toPath (io/file path))))))

(defn- temp [suffix]
  (doto (java.io.File/createTempFile "gd-edit-stash-tabs" suffix) .deleteOnExit))

;; ----------------------------------------------------------------- reading

(deftest personal-stash-tab-names
  (let [tabs (:stashes (gdc/load-character-file* character))]
    (testing "the file loads at all -- it does not on a build that reads the name as an int"
      (is (seq tabs)))

    (testing "named tabs come back with their names"
      (is (= ["AAA" "BB"] (mapv :tab-name (take 2 tabs)))))

    (testing "tabs nobody renamed are empty, not nil -- a zero-length name is still a name"
      (is (every? #(= "" (:tab-name %)) (drop 2 tabs))))

    (testing "the icon and colour sit beside the name"
      ;; set in game as sword/red and chest/purple; both are 0-based UI positions
      (is (= [1 11] [(:icon-background-color (first tabs)) (:icon (first tabs))]))
      (is (= [8 0]  [(:icon-background-color (second tabs)) (:icon (second tabs))])))))

(deftest transfer-stash-tab-names
  (let [tabs (:stash (stash/load-stash-file transfer))]
    (testing "the transfer stash loads"
      (is (seq tabs)))

    (testing "named tabs come back with their names"
      (is (= ["ZZZZ" "Q"] (mapv :tab-name (take 2 tabs)))))

    (testing "and the rest are unnamed"
      (is (every? #(= "" (:tab-name %)) (drop 2 tabs))))

    (testing "icon and colour, set in game as gun/yellow and book/purple"
      (is (= [3 5] [(:icon-background-color (first tabs)) (:icon (first tabs))]))
      (is (= [8 2] [(:icon-background-color (second tabs)) (:icon (second tabs))])))))

;; ----------------------------------------------------------------- writing
;;
;; Reading a name correctly is only half of it. gd-edit writes these files back,
;; and a field read as the wrong type will be written as the wrong type -- which
;; is how a misread stash would have been corrupted rather than merely refused.

(deftest round-trips-byte-for-byte
  (testing "a character file with named tabs is rewritten exactly as it was read"
    (let [out (temp ".gdc")]
      (gdc/write-character-file (gdc/load-character-file* character) (str out))
      (is (= (sha character) (sha (str out)))
          "rewriting changed the bytes -- a field is not being written as it was read")
      (is (= ["AAA" "BB"]
             (mapv :tab-name (take 2 (:stashes (gdc/load-character-file* (str out))))))
          "the names did not survive a write")))

  (testing "a transfer stash with named tabs is rewritten exactly as it was read"
    (let [out (temp ".gst")]
      (stash/write-stash-file (stash/load-stash-file transfer) (str out))
      (is (= (sha transfer) (sha (str out)))
          "rewriting changed the bytes -- a field is not being written as it was read")
      (is (= ["ZZZZ" "Q"]
             (mapv :tab-name (take 2 (:stash (stash/load-stash-file (str out))))))
          "the names did not survive a write"))))
