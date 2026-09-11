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
            [gd-edit.sheet.data :as data]
            [gd-edit.sheet.tex :as tex]))

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

(defn- jpeg-uri
  "`img` as a JPEG data URI. JPEG has no alpha, so it is drawn onto an opaque
  surface first -- writing an ARGB image straight out comes back wrong."
  [^java.awt.image.BufferedImage img]
  (let [flat (java.awt.image.BufferedImage. (.getWidth img) (.getHeight img)
                                            java.awt.image.BufferedImage/TYPE_INT_RGB)
        g (.createGraphics flat)]
    (.drawImage g img 0 0 java.awt.Color/BLACK nil)
    (.dispose g)
    (let [w (first (iterator-seq (javax.imageio.ImageIO/getImageWritersByFormatName "jpeg")))
          bo (java.io.ByteArrayOutputStream.)
          out (javax.imageio.ImageIO/createImageOutputStream bo)
          param (doto (.getDefaultWriteParam w)
                  (.setCompressionMode javax.imageio.ImageWriteParam/MODE_EXPLICIT)
                  (.setCompressionQuality 0.86))]
      (.setOutput w out)
      (.write w nil (javax.imageio.IIOImage. flat nil nil) param)
      (.dispose w)
      (.close out)
      (str "data:image/jpeg;base64,"
           (.encodeToString (java.util.Base64/getEncoder) (.toByteArray bo))))))

(defn- picture-uri
  "One picture as a data URI, no larger than `cap` on its longest side.

  A screenshot off a modern display is several megabytes, and ten of them would
  weigh more than everything else on the page put together. Anything over the
  cap is scaled and re-encoded; anything already under it is passed through
  exactly as it was, so a picture that is small enough keeps its own bytes."
  [path ^long cap]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "no such picture: " path) {:path path})))
    (let [ext (str/lower-case (or (last (str/split (.getName f) #"\.")) ""))
          mime (case ext ("jpg" "jpeg") "jpeg" "png" "png" "webp" "webp"
                     (throw (ex-info (str "a portrait must be PNG, JPEG or WebP, not " ext)
                                     {:path path})))
          ;; ImageIO does not read WebP, and returns nil rather than throwing
          img (try (javax.imageio.ImageIO/read f) (catch Exception _ nil))]
      (if (and img (> (max (.getWidth img) (.getHeight img)) cap))
        (jpeg-uri (tex/scale img cap))
        (str "data:image/" mime ";base64,"
             (.encodeToString (java.util.Base64/getEncoder)
                              (java.nio.file.Files/readAllBytes (.toPath f))))))))

(defn- portrait-img
  "The pictures baked into the page, if the caller named any.

  Without them the panel invites the reader to drop one in, which is kept in
  their browser rather than in the file -- so a sheet meant to be sent
  somewhere wants the picture put in at this point. Name several, taken a
  rotation step apart, and the sheet arrives already turning.

  They are handed over hidden: the page lifts them into the stack it turns,
  measuring and centring them on the way, which is the same thing it does with
  a picture dropped on it by hand."
  [paths]
  (when (seq paths)
    (let [n (count paths)
          ;; fewer frames can afford more pixels -- the same budget the page
          ;; applies to a dropped set, so a baked rotation and a dropped one
          ;; come out the same weight
          cap (cond (<= n 2) 900 (<= n 6) 750 :else 560)]
      (str "<div class=\"pbaked\" hidden>"
           (str/join (for [p paths]
                       (str "<img src=\"" (picture-uri p cap) "\" alt=\"\">")))
           "</div>"))))

(defn- escape [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn sheet-data
  "Everything the page needs, as one value.

  Gathering the art is the slow part -- each archive is walked once -- so it is
  done here, alongside the character, rather than per section.

  With `art?` false none of it is read, and the page is written without a single
  one of Crate's pictures in it. Every figure, every tooltip and the whole
  layout survive; what goes is the icons, the constellation artwork and, with
  it, the star plot, which is drawn against the artwork's own coordinates. The
  renderer already falls back wherever a picture is missing, so this asks for
  nothing of it beyond leaving the maps empty."
  [character & {:keys [portrait art?] :or {art? true}}]
  (let [sheet (data/character-data character)
        tree (data/tree-data character)
        devo (data/devotion-data character)
        buffs (data/buff-data character)
        {:keys [icons compicons]} (if art? (art/item-art character) {})]
    {:sheet sheet
     :tree tree
     :devo devo
     :buffs buffs
     :art art?
     :icons (or icons {})
     ;; the prototype looked an icon up through a second map; the names are the
     ;; keys now, so the indirection is gone and this stays for the renderer
     :namemap (zipmap (keys icons) (keys icons))
     :compicons (or compicons {})
     :treeicons (if art? (art/tree-art tree devo buffs) {})
     :resicons (if art? (art/resistance-art) {})
     :portrait (or (portrait-img (if (coll? portrait) portrait (remove nil? [portrait]))) "")}))

(defn document
  "The HTML around the sheet: a title, the styles, then the scripts.

  Kept separate from the data so the shape of the page can be checked without a
  game database behind it -- see sheet_test.clj. The one thing that shape has to
  get right is <body>, and it is the one thing a browser looks like it would
  forgive."
  [title style scripts]
  (str "<!doctype html>\n<meta charset=\"utf-8\">\n"
       "<title>" (escape title) " — Character Sheet</title>\n"
       "<style>\n" style "\n</style>\n"
       ;; <body> has to be written out, even though a browser supplies one for a
       ;; page that has any content of its own. A doctype, a meta, a title, a
       ;; style and a script are all valid *head* content, so a page that is
       ;; nothing but those leaves the parser still filling in the head when the
       ;; last script runs: document.body is null, the renderer throws on the
       ;; first line that touches it, and the page comes out blank. Opening the
       ;; element here puts the parser into the body before any script runs.
       "<body>\n"
       (str/join "\n" (map #(str "<script>" % "</script>") scripts))
       "\n"))

(defn page
  "The finished HTML."
  [character & {:keys [portrait art?] :or {art? true}}]
  (let [d (sheet-data character :portrait portrait :art? art?)]
    (document (get-in d [:sheet :character :name])
              (str (font-face-css) "\n" (resource "gd-edit-sheet.css"))
              ;; the data first, then what makes it respond, then what draws it
              [(str "window.DATA=" (json/write-str d) ";")
               (str "\n" (resource "gd-edit-sheet-behaviour.js") "\n")
               (str "\n" (resource "gd-edit-sheet.js") "\n")])))

(defn write!
  "Write `character`'s sheet to `path`. Returns the file."
  [character path & {:keys [portrait art?] :or {art? true}}]
  (let [f (io/file path)
        ;; a directory means "put it here", and the character names the file
        f (if (or (.isDirectory f) (str/ends-with? (str path) java.io.File/separator))
            (io/file f (str (get-in (data/character-data character) [:character :name])
                            "-sheet.html"))
            f)
        html (page character :portrait portrait :art? art?)]
    (io/make-parents f)
    (spit f html)
    f))
