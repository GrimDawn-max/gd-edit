(ns gd-edit.sheet.render
  "Assembling the character sheet into one file.

  The page carries its own stylesheet, its own data and its own renderer, and
  reaches for nothing: every picture is a data URI, and there is no network code
  in it at all. That is what lets it be kept, opened years later, or sent to
  someone who has neither gd-edit nor the game.

  The markup is built in the browser rather than here. The renderer is a
  transliteration of the one the sheet was prototyped with, which keeps the two
  comparable; assembling HTML in Clojure would have meant rewriting it into a
  different idiom with nothing to check it against."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gd-edit.sheet.art :as art]
            [gd-edit.sheet.data :as data]))

(defn- resource [n]
  (if-let [r (io/resource n)]
    (slurp r)
    (throw (ex-info (str "the sheet is missing part of itself: " n) {:resource n}))))

;; The faces the sheet is set in, carried in the page rather than linked.
;;
;; Linking them would have meant the page reaching out to a font host the first
;; time anyone opened it, which is exactly the thing the sheet is meant not to
;; do -- and it would have degraded to whatever the reader happened to have
;; installed. Both families are under the SIL Open Font License, which allows
;; them to travel like this. Latin subsets only, which is what the page sets.
(def ^:private faces
  [["sheet-fonts/libertinus-serif-400.woff2"        "Libertinus Serif" "normal" 400]
   ["sheet-fonts/libertinus-serif-600.woff2"        "Libertinus Serif" "normal" 600]
   ["sheet-fonts/libertinus-serif-700.woff2"        "Libertinus Serif" "normal" 700]
   ["sheet-fonts/libertinus-serif-400-italic.woff2" "Libertinus Serif" "italic" 400]
   ["sheet-fonts/libertinus-mono-400.woff2"         "Libertinus Mono"  "normal" 400]
   ["sheet-fonts/jura-500.woff2"                    "Jura"             "normal" 500]
   ["sheet-fonts/jura-600.woff2"                    "Jura"             "normal" 600]
   ["sheet-fonts/jura-700.woff2"                    "Jura"             "normal" 700]])

(defn- font-face-css []
  (str/join
   "\n"
   (for [[n family style weight] faces]
     (let [r (or (io/resource n)
                 (throw (ex-info (str "the sheet is missing one of its fonts: " n)
                                 {:resource n})))
           bytes (with-open [in (io/input-stream r)
                             out (java.io.ByteArrayOutputStream.)]
                   (io/copy in out)
                   (.toByteArray out))]
       (format (str "@font-face{font-family:'%s';font-style:%s;font-weight:%d;"
                    "font-display:swap;src:url(data:font/woff2;base64,%s) format('woff2')}")
               family style weight
               (.encodeToString (java.util.Base64/getEncoder) bytes))))))

(defn- portrait-img
  "A picture baked into the page, if the caller named one.

  Without it the panel invites the reader to drop one in, which is kept in
  their browser rather than in the file -- so a sheet meant to be sent
  somewhere wants the picture put in at this point."
  [path]
  (when path
    (let [f (io/file path)]
      (when-not (.exists f)
        (throw (ex-info (str "no such picture: " path) {:path path})))
      (let [ext (str/lower-case (or (last (str/split (.getName f) #"\.")) ""))
            mime (case ext ("jpg" "jpeg") "jpeg" "png" "png" "webp" "webp"
                       (throw (ex-info (str "a portrait must be PNG, JPEG or WebP, not " ext)
                                       {:path path})))
            bytes (java.nio.file.Files/readAllBytes (.toPath f))]
        (str "<img src=\"data:image/" mime ";base64,"
             (.encodeToString (java.util.Base64/getEncoder) bytes)
             "\" alt=\"\">")))))

(defn- escape [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn sheet-data
  "Everything the page needs, as one value.

  Gathering the art is the slow part -- each archive is walked once -- so it is
  done here, alongside the character, rather than per section."
  [character & {:keys [portrait]}]
  (let [sheet (data/character-data character)
        tree (data/tree-data character)
        devo (data/devotion-data character)
        buffs (data/buff-data character)
        {:keys [icons compicons]} (art/item-art character)]
    {:sheet sheet
     :tree tree
     :devo devo
     :buffs buffs
     :icons icons
     ;; the prototype looked an icon up through a second map; the names are the
     ;; keys now, so the indirection is gone and this stays for the renderer
     :namemap (zipmap (keys icons) (keys icons))
     :compicons compicons
     :treeicons (art/tree-art tree devo buffs)
     :resicons (art/resistance-art)
     :portrait (or (portrait-img portrait) "")}))

(defn page
  "The finished HTML."
  [character & {:keys [portrait]}]
  (let [d (sheet-data character :portrait portrait)
        nm (get-in d [:sheet :character :name])]
    (str "<!doctype html>\n<meta charset=\"utf-8\">\n"
         "<title>" (escape nm) " — Character Sheet</title>\n"
         "<style>\n" (font-face-css) "\n" (resource "gd-edit-sheet.css") "\n</style>\n"
         ;; the data first, then what draws it, then what makes it respond
         "<script>window.DATA=" (json/write-str d) ";</script>\n"
         "<script>\n" (resource "gd-edit-sheet-behaviour.js") "\n</script>\n"
         "<script>\n" (resource "gd-edit-sheet.js") "\n</script>\n")))

(defn write!
  "Write `character`'s sheet to `path`. Returns the file."
  [character path & {:keys [portrait]}]
  (let [f (io/file path)
        ;; a directory means "put it here", and the character names the file
        f (if (or (.isDirectory f) (str/ends-with? (str path) java.io.File/separator))
            (io/file f (str (get-in (data/character-data character) [:character :name])
                            "-sheet.html"))
            f)
        html (page character :portrait portrait)]
    (io/make-parents f)
    (spit f html)
    f))
