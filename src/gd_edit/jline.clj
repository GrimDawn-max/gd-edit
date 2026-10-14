(ns gd-edit.jline
  "Line editing, on jline 3.

  Moved off jline 2 because that artifact bundled its own copy of jansi's
  classes -- same package, older code, its own native loader -- which shadowed
  the real jansi on the classpath and had no native library for any arm64
  platform. jline 3 ships no such copy.

  Two differences from jline 2 matter here, and both are easy to miss because
  neither shows up on a terminal:

  End of input. jline 2's `readLine` returned nil when stdin ran out; jline 3
  throws `EndOfFileException` instead. Everything that drives gd-edit from a
  file or a pipe -- batch mode, every scripted run -- depends on nil, so it is
  translated back here rather than left for each caller to discover.

  No terminal. A system terminal cannot be built when stdin is a pipe, and jline
  3 refuses rather than guessing unless a dumb terminal is explicitly allowed.
  `.dumb true` allows it, which is what a redirected or piped run gets: no line
  editing, no history, reading lines as before."
  (:require [gd-edit.utils :as u])
  (:import [org.jline.reader LineReader LineReaderBuilder EndOfFileException UserInterruptException]
           [org.jline.terminal Terminal TerminalBuilder]))

(def ^:redef use-jline false)
(def ^:redef reader nil)
(def ^:redef terminal nil)
;; set by `set-input` for batch mode; nil the rest of the time
(def ^:redef input-reader nil)

(defn- build-reader
  ^LineReader [^Terminal t]
  (-> (LineReaderBuilder/builder)
      (.terminal t)
      (.build)))

(defn- install!
  [^Terminal t]
  (alter-var-root #'terminal (constantly t))
  (alter-var-root #'reader (constantly (build-reader t)))
  (alter-var-root #'use-jline (constantly true)))

(defn- quiet!
  "Stop jline narrating its fallback.

  With no terminal to attach to -- any piped or redirected run, and batch mode --
  jline logs \"Unable to create a system terminal, creating a dumb terminal\" at
  WARNING through java.util.logging, which lands on stderr in front of the user.
  The fallback is deliberate here, so the message is noise."
  []
  (.setLevel (java.util.logging.Logger/getLogger "org.jline")
             java.util.logging.Level/OFF))

(defn initialize
  []
  (quiet!)
  (install! (-> (TerminalBuilder/builder)
                (.system true)
                ;; a piped or redirected run has no terminal to attach to, and
                ;; refusing to start would be worse than losing line editing
                (.dumb true)
                (.build))))

(defn set-input
  "Read subsequent lines from `input-stream` -- how batch mode replaces stdin.

  jline is left out of this entirely. A file needs no line editing, no history
  and no terminal, and routing one through jline 3 fought it: `.streams` alone
  still builds a *system* terminal that reads the real stdin, so batch mode sat
  at the prompt waiting for a human, and the reader emitted terminal setup
  sequences into the output on its way there. Reading the stream directly is
  both simpler and what jline 2's `setInput` amounted to."
  [input-stream]
  (alter-var-root #'use-jline (constantly false))
  (alter-var-root #'input-reader
                  (constantly (java.io.BufferedReader.
                               (java.io.InputStreamReader. input-stream)))))

(defn readline
  ([]
   (readline ""))
  ([prompt]

   ;; For some reason, using calling jline's readline throws an exception
   ;; when invoked from the cider repl
   (cond
     ;; batch mode: straight off the file
     input-reader
     (do (u/print- prompt)
         (.readLine ^java.io.BufferedReader input-reader))

     (not use-jline)
     (do (u/print- prompt)
         (clojure.core/read-line))

     :else
     (try
       (.readLine ^LineReader reader prompt)
       ;; nil is what jline 2 gave at end of input, and what every caller here
       ;; already handles
       (catch EndOfFileException _ nil)
       ;; Defensive only. jline 3 raises this when the reader itself handles
       ;; ctrl-c, which needs the interrupt option turned on; left off, as
       ;; jline 2 left it, ctrl-c reaches the JVM as SIGINT and quits -- which
       ;; is what every release has done and what was confirmed on a terminal
       ;; here. Turning it on would make ctrl-c cancel the line instead, a
       ;; change in behaviour rather than a fix, so it stays off.
       (catch UserInterruptException _ nil)))))
