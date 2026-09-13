(ns gd-edit.sheet-test
  "The shape of the page the sheet is written into.

  The markup itself is built in the browser, so almost nothing about the sheet
  can be checked here. What can be checked is the skeleton around it -- and that
  skeleton has exactly one way to fail silently, which it has already failed
  once: leave <body> out, and every browser still renders a page, just an empty
  one, because the renderer's first call is document.body.insertAdjacentHTML and
  document.body is null while the parser is still in the head."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [gd-edit.item-stats :as is]
            [gd-edit.whats-new :as wn]
            [gd-edit.sheet.render :as render]))

(def ^:private doc (render/document "Mary" "body{color:red}" ["one();" "two();"]))

(deftest page-skeleton
  (testing "the parser is in the body before any script runs"
    (is (str/includes? doc "<body>"))
    (is (< (.indexOf doc "<body>") (.indexOf doc "<script>"))
        "a script ahead of <body> runs while document.body is still null"))

  (testing "the page announces what it is"
    (is (str/starts-with? doc "<!doctype html>"))
    (is (str/includes? doc "<meta charset=\"utf-8\">"))
    (is (str/includes? doc "<title>Mary — Character Sheet</title>")))

  (testing "the styles arrive before the scripts that depend on them"
    (is (< (.indexOf doc "<style>") (.indexOf doc "<script>"))))

  (testing "every script is written out, in the order given"
    (is (str/includes? doc "<script>one();</script>"))
    (is (str/includes? doc "<script>two();</script>"))
    (is (< (.indexOf doc "one();") (.indexOf doc "two();"))))

  (testing "a name that could close a tag cannot"
    (is (str/includes? (render/document "<script>x</script>" "" [])
                       "&lt;script&gt;"))))

(deftest conversion-range-is-printable
  (testing "a conversion endpoint does not carry the engine's float noise"
    ;; 30% at the bottom of a 20% jitter: the engine narrows its factor to a
    ;; 32-bit float on purpose, which lands on 24.00000035762787
    (is (= 24.0 (is/display-round 24.00000035762787)))
    (is (= 36.0 (is/display-round 36.000001430511475)))
    (is (= 20.0 (is/display-round 20.000000298023224)))
    (is (= 30.0 (is/display-round 30.000001192092896))))

  (testing "a genuinely fractional range survives"
    (is (= 12.5 (is/display-round 12.5)))
    (is (= 0.75 (is/display-round 0.75)))
    (is (= 2.0001 (is/display-round 2.0001)))))

(deftest release-highlights
  ;; The escaping is the whole difficulty here, and it shipped wrong once: the
  ;; regex matched two backslashes where one was meant, so the body never
  ;; matched and the feature was silently dead. Every test of the parser passed,
  ;; because the parser was never what was broken.
  (let [response (str "{\"tag_name\":\"v9.9.9\",\"body\":\""
                      "## What's new\\n\\n"
                      ;; the API sends non-ASCII as UTF-8, not as \\uXXXX
                      "- `write character-sheet` \u2014 a web page\\n"
                      "- a shield reads \\\"34% Chance to block\\\"\\n\\n"
                      "## Fixes\\n\\n- something else\\n"
                      "\",\"draft\":false}")
        lines (wn/highlights-from-json response)]
    (testing "the section is found through the JSON escaping"
      (is (= 2 (count lines)))
      (is (= "• write character-sheet — a web page" (first lines))))

    (testing "a quote inside the notes does not end the body early"
      (is (str/includes? (second lines) "\"34% Chance to block\"")))

    (testing "what is under a later heading is not part of it"
      (is (not-any? #(str/includes? % "something else") lines))))

  (testing "a release with no such section says nothing"
    (is (nil? (wn/highlights-from-json "{\"body\":\"## Fixes\\n\\n- one\\n\"}"))))

  (testing "and nothing at all is survivable"
    (is (nil? (wn/highlights-from-json nil)))
    (is (nil? (wn/highlights-from-json "not json")))))
