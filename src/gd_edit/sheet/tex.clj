(ns gd-edit.sheet.tex
  "Decoding Grim Dawn's .tex files.

  A .tex is a twelve byte prefix -- \"TEX\" and a size -- followed by a DDS.
  The inventory icons are uncompressed 24-bit BGR or 32-bit BGRA, but every
  creature texture and most of the textures on item meshes are block
  compressed: DXT1, DXT3 or DXT5. Each 4x4 block holds two RGB565 endpoints and
  sixteen 2-bit indices between them; DXT3 prefixes eight bytes of explicit
  4-bit alpha, DXT5 two alpha endpoints and sixteen 3-bit indices.

  The DDS carries mip levels after the first surface, which is exactly
  `(bytes-per-block * ceil(w/4) * ceil(h/4))` long -- reading only that avoids
  decoding the smaller copies over the top of the image."
  (:import [java.awt.image BufferedImage]
           [java.awt RenderingHints]
           [javax.imageio ImageIO]))

(defn u16 ^long [^bytes b ^long off]
  (bit-or (bit-and (aget b off) 0xff)
          (bit-shift-left (bit-and (aget b (inc off)) 0xff) 8)))

(defn u32 ^long [^bytes b ^long off]
  (bit-or (bit-and (aget b off) 0xff)
          (bit-shift-left (bit-and (aget b (+ off 1)) 0xff) 8)
          (bit-shift-left (bit-and (aget b (+ off 2)) 0xff) 16)
          (bit-shift-left (bit-and (aget b (+ off 3)) 0xff) 24)))

(defn ub ^long [^bytes b ^long off] (bit-and (aget b off) 0xff))

(defn- rgb565 [^long v]
  (let [r (bit-and (bit-shift-right v 11) 0x1f)
        g (bit-and (bit-shift-right v 5) 0x3f)
        b (bit-and v 0x1f)]
    ;; the low bits are replicated from the high ones, so full-scale stays full
    [(bit-or (bit-shift-left r 3) (bit-shift-right r 2))
     (bit-or (bit-shift-left g 2) (bit-shift-right g 4))
     (bit-or (bit-shift-left b 3) (bit-shift-right b 2))]))

(defn- lerp ^long [^long a ^long b ^long n ^long d]
  (long (/ (+ (* a (- d n)) (* b n)) d)))

(defn- colour-table
  "The four colours a block interpolates between.

  In DXT1 the c0 <= c1 case makes the fourth entry transparent. DXT3 and DXT5
  carry alpha separately, so both endpoints always interpolate in thirds."
  [^bytes b ^long off alpha-elsewhere?]
  (let [c0 (u16 b off) c1 (u16 b (+ off 2))
        [r0 g0 b0] (rgb565 c0)
        [r1 g1 b1] (rgb565 c1)]
    (if (or alpha-elsewhere? (> c0 c1))
      [[r0 g0 b0 255] [r1 g1 b1 255]
       [(lerp r0 r1 1 3) (lerp g0 g1 1 3) (lerp b0 b1 1 3) 255]
       [(lerp r0 r1 2 3) (lerp g0 g1 2 3) (lerp b0 b1 2 3) 255]]
      [[r0 g0 b0 255] [r1 g1 b1 255]
       [(lerp r0 r1 1 2) (lerp g0 g1 1 2) (lerp b0 b1 1 2) 255]
       [0 0 0 0]])))

(defn- alpha-table [^bytes b ^long off]
  (let [a0 (ub b off) a1 (ub b (inc off))]
    (if (> a0 a1)
      (vec (concat [a0 a1] (for [n (range 1 7)] (lerp a0 a1 n 7))))
      (vec (concat [a0 a1] (for [n (range 1 5)] (lerp a0 a1 n 5)) [0 255])))))

;; no primitive hints here: Clojure only allows them on four arguments or fewer
(defn- put! [^BufferedImage img x y w h [r g bl a]]
  (when (and (< x w) (< y h))
    (.setRGB img x y (unchecked-int (bit-or (bit-shift-left (long a) 24)
                                            (bit-shift-left (long r) 16)
                                            (bit-shift-left (long g) 8)
                                            (long bl))))))

(defn decode
  "The first surface of a .tex as a BufferedImage, or nil for a layout we do
  not read."
  ^BufferedImage [^bytes tex]
  (when (> (count tex) 140)
    (let [dds (java.util.Arrays/copyOfRange tex 12 (count tex))
          h (u32 dds 12) w (u32 dds 16)
          fourcc (apply str (map #(char (ub dds (+ 84 %))) (range 4)))
          bits (u32 dds 88)]
      (when (and (pos? w) (pos? h) (< (* w h) 40000000))
        (let [img (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)]
          (cond
            ;; uncompressed, the layout the inventory icons use
            (and (#{24 32} bits) (>= (count dds) (+ 128 (* (quot bits 8) w h))))
            (let [bpp (quot bits 8)]
              (dotimes [y h]
                (dotimes [x w]
                  (let [o (+ 128 (* bpp (+ (* y w) x)))]
                    (put! img x y w h [(ub dds (+ o 2)) (ub dds (+ o 1)) (ub dds o)
                                       (if (= 4 bpp) (ub dds (+ o 3)) 255)]))))
              img)

            (#{"DXT1" "DXT3" "DXT5"} fourcc)
            (let [blk (if (= "DXT1" fourcc) 8 16)
                  bw (quot (+ w 3) 4)
                  bh (quot (+ h 3) 4)]
              (when (>= (count dds) (+ 128 (* blk bw bh)))
                (dotimes [by bh]
                  (dotimes [bx bw]
                    (let [o (+ 128 (* blk (+ (* by bw) bx)))
                          coff (if (= "DXT1" fourcc) o (+ o 8))
                          cols (colour-table dds coff (not= "DXT1" fourcc))
                          bitsv (u32 dds (+ coff 4))
                          alphas (when (= "DXT5" fourcc) (alpha-table dds o))
                          abits (when (= "DXT5" fourcc)
                                  (reduce (fn [acc i]
                                            (+ acc (bit-shift-left (long (ub dds (+ o 2 i)))
                                                                   (* 8 i))))
                                          0 (range 6)))]
                      (dotimes [py 4]
                        (dotimes [px 4]
                          (let [i (+ (* py 4) px)
                                c (nth cols (bit-and (bit-shift-right bitsv (* 2 i)) 3))
                                a (case fourcc
                                    "DXT1" (nth c 3)
                                    "DXT3" (* 17 (bit-and (bit-shift-right
                                                           (u16 dds (+ o (* 2 (quot i 4))))
                                                           (* 4 (mod i 4)))
                                                          0xf))
                                    "DXT5" (nth alphas (bit-and (bit-shift-right abits (* 3 i)) 7)))]
                            (put! img (+ (* bx 4) px) (+ (* by 4) py) w h
                                  (assoc (vec c) 3 a))))))))
                img))
            :else nil))))))

(defn scale
  "`img` no larger than `cap` on its longest side, keeping its proportions."
  ^BufferedImage [^BufferedImage img ^long cap]
  (let [w (.getWidth img) h (.getHeight img)]
    (if (and (<= w cap) (<= h cap))
      img
      (let [r (min (/ (double cap) w) (/ (double cap) h))
            nw (max 1 (int (Math/round (* w r))))
            nh (max 1 (int (Math/round (* h r))))
            out (BufferedImage. nw nh BufferedImage/TYPE_INT_ARGB)
            g (.createGraphics out)]
        (.setRenderingHint g RenderingHints/KEY_INTERPOLATION
                           RenderingHints/VALUE_INTERPOLATION_BILINEAR)
        (.drawImage g img 0 0 nw nh nil)
        (.dispose g)
        out))))

(defn ->uri
  "A PNG data URI, which is how every image reaches the page."
  [^BufferedImage img]
  (let [bo (java.io.ByteArrayOutputStream.)]
    (ImageIO/write img "png" bo)
    (str "data:image/png;base64,"
         (.encodeToString (java.util.Base64/getEncoder) (.toByteArray bo)))))

(defn ->uri-capped [^BufferedImage img ^long cap]
  (->uri (scale img cap)))
