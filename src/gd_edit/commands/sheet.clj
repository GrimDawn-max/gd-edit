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

(defn write-sheet-handler
  [[_ tokens]]
  (cond
    (not (au/character-loaded?))
    (u/print-line "Load a character first.")

    (empty? tokens)
    (do
      (u/print-line "Give the file to write, so the sheet goes where you meant it to.")
      (u/newline-)
      (u/print-line "    write character-sheet ~/Desktop/mysheet.html")
      (u/print-line "    write character-sheet \"C:\\\\Users\\\\You\\\\Desktop\\\\My Sheet.html\"")
      (u/newline-)
      (u/print-line "A directory works too, and the character names the file.")
      (u/print-line "Add a picture to show beside the gear with:")
      (u/newline-)
      (u/print-line "    write character-sheet ~/Desktop/mysheet.html ~/Desktop/screenshot.png"))

    :else
    ;; ~ is what a path looks like when it is typed rather than pasted, and the
    ;; shell that would have expanded it is not in the way here
    (let [[path portrait] (map #(some-> % u/expand-home) tokens)]
      (try
        (u/print-line "Reading the database for icons and artwork...")
        (let [f (render/write! @globals/character path :portrait portrait)]
          (u/newline-)
          (u/print-line (format "Written %s  (%s)" (.getAbsolutePath f) (size-of f)))
          (u/newline-)
          (u/print-line "It is one file and needs nothing else: no game, no gd-edit and no")
          (u/print-line "internet. Open it in any browser, and hover anything for the detail."))
        (catch clojure.lang.ExceptionInfo ex
          (u/print-line (str "Could not write the sheet: " (ex-message ex))))
        (catch Exception ex
          (u/print-line (str "Could not write the sheet: " (.getMessage ex))))))))
