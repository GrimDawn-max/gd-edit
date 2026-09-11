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
