(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.data.xml :as xml]
            [me.raynes.fs :as fs]

            [clojure.edn :as edn]
            [digest]
            )
  (:import [java.nio.file Path Paths]
           [java.io FileOutputStream]
           )
  )

(def project 'gd-edit)
(def lib 'gd-edit/core)
(def version (format "0.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-standalone.jar" (name project) version))

(def build-java
  "The JDK gd-edit must be built with, which is the oldest JVM it supports.

  Clojure compiles ahead of time, so the bytecode in the uberjar is shaped by
  whichever JDK produced it, not by whichever JDK later runs it. Building on a
  newer release silently bakes in classes the older ones do not have:
  instaparse's FlattenOnDemandVector implements java.util.List, which on JDK 21
  extends java.util.SequencedCollection, and the resulting jar then dies at
  startup with NoClassDefFoundError for everyone on 17. Nothing about that
  failure points at the machine that built it, which is why this is checked
  here rather than left to whoever runs the build remembering."
  17)

(defn- verify-build-jdk
  "Refuse to build on anything but the supported floor.

  Deliberately the first thing a build does, before target/ is touched, so a
  wrong JDK costs nothing but the message."
  []

  (let [running (System/getProperty "java.specification.version")]
    (when (not= running (str build-java))
      (binding [*out* *err*]
        (println)
        (println (format "  Cannot build: gd-edit must be built with JDK %d, but this is JDK %s."
                         build-java running))
        (println)
        (println "  Clojure compiles ahead of time, so a jar built on a newer JDK can")
        (println "  reference classes that do not exist on older ones. It will look fine")
        (println "  here and fail at startup for anyone running the oldest supported JDK.")
        (println)
        (println "  Build with:")
        (println (format "      JAVA_HOME=$(/usr/libexec/java_home -v %d) clojure -T:build dist"
                         build-java))
        (println))
      (System/exit 1))))

(defn shell
  [cmd-str]
  (apply clojure.java.shell/sh (str/split cmd-str #" ")))

(defn shell-stream
  [cmd-str]
  (.waitFor (-> (ProcessBuilder. (str/split cmd-str #" ")) .inheritIO .start)))

(defn git-sha
  []
  (str/trim ((shell "git rev-parse --short HEAD") :out)))

(defn git-tag
  []
  (str/trim ((shell "git describe --abbrev=0 --tags HEAD") :out))
  )

(defn git-branch
  []
  (str/trim ((shell "git rev-parse --abbrev-ref HEAD") :out)))

(defn git-has-uncommitted-changes
  []

  (if (= (:exit (shell "git diff-index --quiet HEAD --")) 1)
    true
    false))

(defn make-build-info
  ([]
   (make-build-info {}))

  ([kv]
   (merge
    {:app-name project
     :version version

     :sha (git-sha)
     :tag (git-tag)
     :branch (git-branch)
     :timestamp (clojure.instant/read-instant-timestamp ((shell "git log -1 --pretty=format:%cd --date=iso-strict") :out))}
    kv)))

(defn- to-four-places
  [version]
  (let [places (str/split version #"\.")]
    (try
      (doall (map #(Integer/parseInt %) places))
      (if (> (count places) 4) (throw (NumberFormatException.)))
      (catch NumberFormatException e
        (throw (Exception. "Version string must consist of up to four numbers, separated by periods."))))
    (str/join "." (concat places (repeat (- 4 (count places)) "0")))))

(defn- filename-without-path
  [file]
  (let [path (Paths/get (.getPath file) (into-array [""]))]
    (str (.getFileName path))))

(defn launch4j-config
  [{:keys [out-file jar-file main-class
           project-name description version copyright
           jvm-opts jre-path]}]
  (xml/sexp-as-element
   [:launch4jConfig
    [:headerType "console"]
    [:outfile (.getAbsolutePath out-file)]
    [:jar (.getAbsolutePath jar-file)]
    [:classPath
     [:mainClass main-class]]
    (into [:jre
           (if jre-path
             [:path jre-path]
             [:path])
           [:minVersion "1.7.0"]
           [:maxHeapSize "2048"]
           [:jdkPreference "preferJdk"]]
          (for [jvm-opt jvm-opts]
            [:opt jvm-opt]))
    [:versionInfo
     [:fileVersion (to-four-places version)]
     [:txtFileVersion version]
     [:fileDescription description]
     [:copyright copyright]
     [:productVersion (to-four-places version)]
     [:txtProductVersion version]
     [:productName project-name]
     [:internalName project-name]
     [:originalFilename (filename-without-path out-file)]]]))

(defn write-launch4j-config
  [opts file-writer]
  (xml/emit (launch4j-config opts) file-writer))

(defn build-exe
  [{:keys [uber-file project-name main description version copyright jvm-opt jre-path]}]

  (let [jar    (b/resolve-path uber-file)
        tmp    (fs/temp-dir "gd-edit-build-exe")

        fname  (->> (.getName jar)
                    (re-matches #"(.+)\.jar")
                    second)

        xml-fname  (str fname ".xml")
        exe-fname  (str fname ".exe")
        xml-file   (io/file tmp xml-fname)
        out-file   (io/file (.getParent (io/file uber-file)) exe-fname)
        ]
    (with-open [xml (io/writer xml-file :encoding "UTF-8")]
      (write-launch4j-config {:jar-file     jar
                              :out-file     out-file
                              :main-class   main
                              :project-name project-name
                              :description  description
                              :version      version
                              :copyright    copyright
                              :jvm-opts     (or jvm-opt #{})
                              :jre-path     jre-path}
                             xml))
    (shell-stream (format "launch4j %s" (.getPath xml-file)))
    out-file))

(defn- bin-header
  [jvm-opts]
  (format "#!/bin/sh\n\nexec java %s -jar $0 \"$@\"\n\n\n"
          (str/join \space jvm-opts)))

(defn build-bin
  [{:keys [uber-file project-name main description version copyright jvm-opt jre-path header]}]
    (let [jar (b/resolve-path uber-file)
          tmp (fs/temp-dir "gd-edit-build-bin")
          header (if header
                   (slurp header)
                   (bin-header (or jvm-opt #{})))

          bin-fname  (->> (.getName jar)
                          (re-matches #"(.+)\.jar")
                          second)
          tgt-file   (io/file (.getParent (io/file uber-file)) bin-fname)
          ]
      (println (format "Creating %s binary..." bin-fname))
      (with-open [bin (FileOutputStream. tgt-file)]
        (io/copy header bin)
        (io/copy jar bin))
      (.setExecutable tgt-file true false)
      tgt-file))

(comment
  (build-exe {:uber-file uber-file
              :main 'gd_edit.core
              :jvm-opt #{"-Xms128m" "-Djna.nosys=true"}
              :project-name project
              :description "GrimDawn save game editor"
              :copyright "2022"
              :version version
              }
             )

  (build-bin {:uber-file uber-file
              :main 'gd_edit.core
              :jvm-opt #{"-Xms128m" "-Djna.nosys=true"}
              :project-name project
              :description "GrimDawn save game editor"
              :copyright "2022"
              :version version
              }
             )

  )

(def build-stage-atom (atom {:count 0}))
(defn print-build-stage
  [text]

  (swap! build-stage-atom update :count inc)
  (println (format "[%d] %s" (:count @build-stage-atom) text)))

(defn clean
  [_]
  (print-build-stage "Cleaning old build files...")
  (b/delete {:path "target"}))

(defn- compile-stat-engine
  "Compile the item stat engine into the jar, if its sources are here.

  The engine is a third-party component kept outside version control, so most
  checkouts will not have it. gd-edit runs either way -- item summaries fall
  back to unrolled values and seed searching reports itself unavailable -- so
  its absence is a normal build, not a broken one."
  []
  (when (.isDirectory (java.io.File. "java-src"))
    (print-build-stage "Compiling item stat engine...")
    (b/javac {:src-dirs ["java-src"]
              :class-dir class-dir
              :basis basis
              :javac-opts ["--release" "17"]})))

(defn uber
  [_]
  (verify-build-jdk)
  (clean nil)

  ;; Pack build info as "build.edn" with the jar
  (print-build-stage "Creating build.edn...")
  (b/write-file {:path (format "%s/build.edn" class-dir)
                 :content (make-build-info)})

  (print-build-stage "Copying sources...")
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})

  (compile-stat-engine)

  (print-build-stage "Compilng sources...")
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})

  (print-build-stage "Building uberjar...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'gd-edit.core})
  )

(defn build
  [_]
  (uber {})

  (let [settings {:uber-file uber-file
                  :main 'gd_edit.core
                  :jvm-opt #{"-Xms128m" "-Djna.nosys=true"}
                  :project-name project
                  :description "GrimDawn save game editor"
                  :copyright "2022"
                  :version version}

        _ (print-build-stage "Building nix binary...")
        bin-file (build-bin settings)

        _ (print-build-stage "Building exe...")
        exe-file (build-exe settings)]

    {:bin-file bin-file
     :exe-file exe-file}))


;;------------------------------------------------------------------------------
;; Distribution archives
;;
;; Produces one zip per platform, each self-contained: the jar, a launcher, and
;; the docs. No Java runtime is bundled, which is what lets all three archives be
;; built from a single machine -- see packaging/common/README.txt.
;;------------------------------------------------------------------------------

(def dist-dir "target/dist")

(defn- copy-file
  [src dst]
  (io/make-parents dst)
  (io/copy (io/file src) (io/file dst)))

(defn- crlf
  "Rewrite line endings for text bound for Windows. cmd.exe misparses an LF-only
  .bat: it fails with \"The filename, directory name, or volume label syntax is
  incorrect\", which points nowhere near the real cause. Everything we package is
  authored on macOS, so the conversion has to happen here."
  [s]
  (-> s (str/replace "\r\n" "\n") (str/replace "\n" "\r\n")))

(defn- copy-template
  "Copy a text file, substituting @VERSION@. `xform` rewrites the result, and is
  where `crlf` gets applied for the Windows dist."
  ([src dst] (copy-template src dst identity))
  ([src dst xform]
   (io/make-parents dst)
   (spit dst (xform (str/replace (slurp src) "@VERSION@" version)))))

(defn- copy-common-docs
  ([target] (copy-common-docs target identity))
  ([target xform]
   (copy-template "packaging/common/README.txt" (str target "/README.txt") xform)
   (copy-template "packaging/common/THIRD-PARTY.txt" (str target "/THIRD-PARTY.txt") xform)
   (copy-template "LICENSE" (str target "/LICENSE.txt") xform)))

(defn- make-executable
  [path]
  (.setExecutable (io/file path) true false))

(defn- zip-dir
  "Zip the contents of `dir` into `zipfile`. Uses the system zip so that the unix
  executable bits on the launchers survive into the archive."
  [dir zipfile]
  (let [zip (.getAbsolutePath (io/file zipfile))]
    (fs/delete zip)
    (let [{:keys [exit err]} (clojure.java.shell/sh "zip" "-q" "-r" zip "." :dir dir)]
      (when-not (zero? exit)
        (throw (ex-info (str "zip failed: " err) {:dir dir}))))
    zip))

(defn- dist-macos
  [jar]
  (let [root (str dist-dir "/macos")
        app  (str root "/gd-edit.app")]
    ;; The jar lives inside the bundle so the .app stays self-contained if it is
    ;; moved out of the unzipped folder.
    (copy-file jar (str app "/Contents/Java/gd-edit-standalone.jar"))
    (copy-template "packaging/macos/gd-edit.app/Contents/Info.plist"
                   (str app "/Contents/Info.plist"))
    (copy-file "packaging/macos/gd-edit.app/Contents/Resources/GDIcon.icns"
               (str app "/Contents/Resources/GDIcon.icns"))
    (copy-file "packaging/macos/gd-edit.app/Contents/MacOS/gd-edit"
               (str app "/Contents/MacOS/gd-edit"))
    (copy-file "packaging/common/gd-edit-launcher.sh"
               (str app "/Contents/MacOS/run-gd-edit.sh"))
    (make-executable (str app "/Contents/MacOS/gd-edit"))
    (make-executable (str app "/Contents/MacOS/run-gd-edit.sh"))

    ;; Terminal-friendly launcher alongside the bundle. It finds the jar inside
    ;; the .app, so there is only ever one copy of it.
    (copy-file "packaging/common/gd-edit-launcher.sh" (str root "/gd-edit.command"))
    (make-executable (str root "/gd-edit.command"))

    (copy-common-docs root)
    (zip-dir root (format "%s/gd-edit-%s-macos.zip" dist-dir version))))

(defn- dist-linux
  [jar]
  (let [root (str dist-dir "/linux")]
    (copy-file jar (str root "/gd-edit-standalone.jar"))
    (copy-file "packaging/common/gd-edit-launcher.sh" (str root "/gd-edit.sh"))
    (make-executable (str root "/gd-edit.sh"))
    (copy-common-docs root)
    (zip-dir root (format "%s/gd-edit-%s-linux.zip" dist-dir version))))

(defn- dist-windows
  [jar]
  (let [root (str dist-dir "/windows")]
    (copy-file jar (str root "/gd-edit-standalone.jar"))
    (copy-template "packaging/windows/gd-edit.bat" (str root "/gd-edit.bat") crlf)
    (copy-common-docs root crlf)
    (zip-dir root (format "%s/gd-edit-%s-windows.zip" dist-dir version))))

(defn dist
  "Build the uberjar and package a release zip for each platform."
  [_]
  (uber {})

  (let [jar (b/resolve-path uber-file)]
    (print-build-stage "Packaging macOS...")
    (let [z (dist-macos jar)] (println "   " z))

    (print-build-stage "Packaging Windows...")
    (let [z (dist-windows jar)] (println "   " z))

    (print-build-stage "Packaging Linux...")
    (let [z (dist-linux jar)] (println "   " z)))

  (println "\nRelease archives are in" dist-dir))

(defn replace-path-extension
  [path new-ext]

  (as-> (io/file path) $
    (fs/base-name $ true)
    (io/file (fs/parent path) $)
    (str $ new-ext)))

(defn replace-base-name-extension
  [basename new-ext]

  (-> (io/file basename)
      (fs/base-name true)
      (str new-ext)))

;; The upstream project published builds to the author's Dropbox and had the app
;; download them. Releases are now zips attached to a GitHub release, made by
;; running `dist` and uploading the result -- see RELEASING.md.

(comment


  (uber {})
  (build {})

  (publish {})

  )
