(ns gd-edit.self-update
  "Checking whether a newer release of gd-edit exists."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [gd-edit.utils :as u])
  (:import java.io.IOException))

(defn fetch-url [url-str]
  (let [response (client/get url-str
                             {:headers {"User-Agent" "curl/7.43.0"}})]
    (if (not= (response :status) 200)
      (throw (IOException. (str "Got response status:" (response :status))))

      (response :body))))

(defn get-build-info
  "Get the build info associated with the current build"
  []
  (when-let [info-file (io/resource "build.edn")]
    (edn/read-string (slurp info-file))))

(def releases-url
  "Where a user gets a newer build."
  "https://github.com/GrimDawn-max/gd-edit/releases/latest")

(def ^:private releases-api
  "https://api.github.com/repos/GrimDawn-max/gd-edit/releases/latest")

(defn fetch-latest-release-tag
  "The tag of the newest published release, or nil.

  Replaces the original self-update endpoints, which pointed at the upstream
  author's Dropbox: this fork would otherwise have checked his builds daily and,
  on running `update`, downloaded and installed one over itself.

  This only asks what the newest release is. It does not download anything.
  Self-replacement is not viable here anyway -- a release is a zip holding a
  launcher and a jar, not the single executable the old mechanism swapped."
  []
  (try
    (let [body (fetch-url releases-api)
          tag  (second (re-find #"\"tag_name\"\s*:\s*\"([^\"]+)\"" body))]
      (not-empty tag))
    (catch Throwable _ nil)))

(defn- version-of
  "The numeric parts of a version string, for comparison. \"v0.2.468\" -> [0 2 468]"
  [s]
  (when s (mapv #(Long/parseLong %) (re-seq #"\d+" s))))

(defn newer-release-available?
  "The newest release tag if it is ahead of what is running, otherwise nil."
  []
  (when-let [tag (fetch-latest-release-tag)]
    (let [current (:version (get-build-info))
          latest  (version-of tag)
          running (version-of current)]
      (when (and latest running (pos? (compare latest running)))
        tag))))

(defn fetch-has-new-version?
  "Whether a newer release exists.

  Returns [:new-version-available tag] or [:up-to-date]. Never throws -- the
  caller runs this on startup, and a network problem should not interrupt
  someone editing a save file."
  []
  (try
    (if-let [tag (newer-release-available?)]
      [:new-version-available tag]
      [:up-to-date])
    (catch Throwable _ [:up-to-date])))

(defn try-self-update
  "Tell the user where to get a newer build, if there is one.

  gd-edit no longer updates itself. The original mechanism downloaded a single
  executable and swapped it in place; a release is now a zip containing a
  launcher and a jar, so there is nothing equivalent to swap. Replacing that
  safely -- while the jar is running, across three platforms -- is a great deal
  of machinery for something a browser does in one click.

  It also pointed at the upstream author's Dropbox, which is still live. Left
  alone, this fork would have offered to install his 2021 build over itself."
  []
  (let [[status tag] (fetch-has-new-version?)]
    (if (= status :new-version-available)
      (do
        (u/print-line (format "A newer release is available: %s" tag))
        (u/print-line (format "You are running %s" (:version (get-build-info))))
        (u/print-line)
        (u/print-line "Download it from:")
        (u/print-line (str "    " releases-url))
        (u/print-line)
        (u/print-line "Unzip it over your current folder, or anywhere you like.")
        :new-version-available)
      :up-to-date)))
