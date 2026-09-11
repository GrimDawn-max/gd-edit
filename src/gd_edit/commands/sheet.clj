(ns gd-edit.commands.sheet
  "`write character-sheet` -- the loaded character as a web page."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.app-util :as au]
            [gd-edit.globals :as globals]
            [gd-edit.sheet.render :as render]
            [gd-edit.utils :as u]))

(defn- size-of [f]
  (let [n (.length ^java.io.File f)]
    (if (< n 1048576)
      (format "%d KB" (quot n 1024))
      (format "%.1f MB" (/ n 1048576.0)))))

;; --no-art writes the sheet with none of the game's pictures in it. Anything
;; beginning with a dash is a switch rather than a path, so an unknown one is
;; worth saying so about: silently treating it as a filename would write the
;; sheet to a file called "--no-arts".
(def ^:private switches #{"--no-art"})

(defn- split-args [tokens]
  (let [flag? #(str/starts-with? % "-")]
    {:flags (set (filter flag? tokens))
     :paths (remove flag? tokens)}))

(defn write-sheet-handler
  [[_ tokens]]
  (let [{:keys [flags paths]} (split-args tokens)
        unknown (remove switches flags)]
    (cond
      (not (au/character-loaded?))
      (u/print-line "Load a character first.")

      (seq unknown)
      (do
        (u/print-line (format "Don't know the switch %s." (first unknown)))
        (u/newline-)
        (u/print-line "The only one is --no-art, which leaves the game's pictures out."))

      (empty? paths)
      (do
        (u/print-line "Give the file to write, so the sheet goes where you meant it to.")
        (u/newline-)
        (u/print-line "    write character-sheet ~/Desktop/mysheet.html")
        (u/print-line "    write character-sheet \"C:\\Users\\You\\Desktop\\My Sheet.html\"")
        (u/newline-)
        (u/print-line "A directory works too, and the character names the file.")
        (u/print-line "Add a picture to show beside the gear with:")
        (u/newline-)
        (u/print-line "    write character-sheet ~/Desktop/mysheet.html ~/Desktop/screenshot.png")
        (u/newline-)
        (u/print-line "Add --no-art for a sheet with none of the game's pictures in it,")
        (u/print-line "which is the one to post somewhere public:")
        (u/newline-)
        (u/print-line "    write character-sheet ~/Desktop/mysheet.html --no-art"))

      :else
      ;; ~ is what a path looks like when it is typed rather than pasted, and the
      ;; shell that would have expanded it is not in the way here
      (let [[path portrait] (map #(some-> % u/expand-home) paths)
            art? (not (contains? flags "--no-art"))]
        (try
          (u/print-line (if art?
                          "Reading the database for icons and artwork..."
                          "Reading the database..."))
          (let [f (render/write! @globals/character path
                                 :portrait portrait :art? art?)]
            (u/newline-)
            (u/print-line (format "Written %s  (%s)" (.getAbsolutePath f) (size-of f)))
            (u/newline-)
            (if art?
              (do
                (u/print-line "It is one file and needs nothing else: no game, no gd-edit and no")
                (u/print-line "internet. Open it in any browser, and hover anything for the detail."))
              (do
                (u/print-line "Written without the game's pictures, so there is nothing of Crate's")
                (u/print-line "artwork in it. Every figure and every tooltip is still there.")))) 
          (catch clojure.lang.ExceptionInfo ex
            (u/print-line (str "Could not write the sheet: " (ex-message ex))))
          (catch Exception ex
            (u/print-line (str "Could not write the sheet: " (.getMessage ex)))))))))
