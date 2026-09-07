(ns gd-edit.commands.delete
  (:require [clojure.java.io :as io]
            [gd-edit.utils :as u]
            [jansi-clj.core :refer [red yellow]]
            [gd-edit.app-util :as au]
            [gd-edit.globals :as globals]
            [gd-edit.stack :as stack]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.commands.choose-character :as commands.choose-character])
  (:import [com.sun.jna.platform FileUtils]
           [java.awt Desktop Desktop$Action]))

(declare character-selection-screen!)

(defn- move-to-trash!
  "Move a file or folder to the system trash. True when it actually went.

  Prefers java.awt.Desktop, which ships with the JDK and handles all three
  platforms with no native library of its own.

  JNA is kept as a fallback for the case where Desktop is unavailable -- a
  headless JVM, mainly. It used to be the only route, and it fails outright on
  Apple Silicon: the native library JNA bundles has no arm64 slice, so the call
  raises UnsatisfiedLinkError. That is an Error rather than an Exception, so the
  catch here has to be Throwable; catching Exception let it escape and take the
  whole delete command down with a stack trace."
  [^java.io.File target]
  (boolean
   (or (try
         (and (Desktop/isDesktopSupported)
              (.isSupported (Desktop/getDesktop) Desktop$Action/MOVE_TO_TRASH)
              (.moveToTrash (Desktop/getDesktop) target))
         (catch Throwable _ false))

       (try
         (.moveToTrash (FileUtils/getInstance) (into-array [target]))
         ;; JNA's moveToTrash returns nothing; reaching here means it worked.
         true
         (catch Throwable _ false)))))

(defn- delete-character-file
  [savepath]

  (let [target (io/file savepath)
        target (cond-> target
                 (.isFile target)
                 (.getParentFile))]
    (u/print-line "Moving character to trash:")
    (u/print-indent 1)
    (u/print-line (yellow target))

    (if-not (move-to-trash! target)
      ;; Say so plainly and change nothing. Unloading the character or returning
      ;; to the selection screen here would suggest the delete had happened.
      (do
        (u/print-line)
        (u/print-line (red "Could not move the character to the trash."))
        (u/print-line "The character has not been deleted. Its files are still at:")
        (u/print-indent 1)
        (u/print-line (yellow target))
        (u/print-line "Delete that folder yourself if you meant to remove it."))

      (do
        (u/print-line "Deleted.")

        ;; If the loaded character was deleted, unload the character from memory
        (when (= (:meta-character-loaded-from @globals/character) (io/file savepath))
          (reset! globals/character {}))

        ;; Stay on the delete menu, refreshed.
        ;;
        ;; This used to drop back to the character *selection* screen, which is
        ;; a numbered list of the same characters that loads rather than deletes.
        ;; Someone removing two characters would choose a number, get the second
        ;; list, choose another number, and have loaded a character while
        ;; believing they had deleted one -- the lists differ only in a line of
        ;; text above them. Any other command still leaves the menu.
        (character-selection-screen!)))))

(defn- character-selection-screen
  []

  ;; grab a list save directories where a "player.gdc" file exists
  ;;
  ;; Sorted by name, to match the character list shown everywhere else. These
  ;; arrive in filesystem order, which put the same characters at different
  ;; numbers in the two menus -- an easy way to delete the wrong one.
  (let [display-name (fn [dir]
                       (let [n (u/last-path-component (.getPath dir))]
                         (if (= \_ (first n)) (subs n 1) n)))
        save-dirs (sort-by display-name (dirs/get-all-save-file-dirs))]

    {:display-fn
     (fn []
       (if (empty? save-dirs)
         (u/print-line (red "No save files found"))
         (u/print-line "Please choose a character to delete:")))

     ;; generate the menu choices
     ;; reduce over save-dirs with each item being [index save-dir-item]
     :choice-map (doall (for [[idx dir] (u/indexed save-dirs)]
                          (let [display-idx (inc idx)]
                            [(str display-idx)                    ; command string
                             (format "%s (%s save)"
                                     (display-name dir)
                                     (dirs/save-dir-type dir))         ; menu display string
                             (fn []                               ; function to run when selected
                               (let [savepath (.getPath (io/file dir "player.gdc"))]
                                 (delete-character-file savepath)))])))}))

(defn character-selection-screen! [] (stack/replace-last! globals/menu-stack (character-selection-screen)))

(defn delete-handler
  [[_ [param]]]

  ;; Did the user provide a parameter?
  (if param

    (or
     ;; The param might be a filepath to the save file
     (let [character-filepath param
           character-file (io/file character-filepath)]
       (when (and (.exists character-file)
                  (.isFile character-file))
         (delete-character-file character-file)
         :ok))

     ;; The param might be a character name
     (let [character-name param
           characters (au/locate-character-files param)]
       (cond
         (zero? (count characters))
         (u/print-line (red "Sorry,") (format "cannot find a character named \"%s\"" character-name))

         (> (count characters) 1)
         (do
           (u/print-line (red "Sorry,") "there is more than one character with that name at:")
           (doseq [f characters]
             (u/print-indent 1)
             (u/print-line (yellow f))))

         :else
         (delete-character-file (first characters)))))

    ;; The user did not provide a name or a path
    ;; Show a menu to let the user choose from
    (character-selection-screen!)))
