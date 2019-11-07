(ns build
  (:require [badigeon.javac :as javac]
            [badigeon.clean :as clean]
            [badigeon.uberjar :as uberjar]
            [clojure.tools.deps.alpha.reader :as deps-reader]
            [badigeon.jar :as jar]
            [badigeon.zip :as zip]
            [badigeon.compile :as compile]
            [badigeon.bundle :as bundle]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import (java.nio.file Path Files CopyOption StandardCopyOption Paths)
           (java.io File)))

(def ^Path out-path (badigeon.bundle/make-out-path 'info.gamlor/bastel-proxy "0.2"))
(def ^Path dist-path (.getPath (io/file "./target/dist")))
(def jar-name (str out-path ".jar"))
(def final-dist-tar (.getCanonicalPath (io/file "./target/bastel-proxy.tar.gz")))
(def final-dist-zip (.toPath (io/file "./target/bastel-proxy.zip")))

(defn javac []
  (println "Compiling Java")
  (javac/javac "src" {:compile-path  "classes"
                      ;; Additional options used by the javac command
                      :javac-options ["-target" "1.8"
                                      "-source" "1.8" "-Xlint:-options"]})
  (println "Compilation Completed"))

(defn uberjar []
  (clean/clean "target")
  (javac)
  (compile/compile `[bastel-proxy.main]
                   {:compile-path "classes"})
  (uberjar/bundle out-path
                  {;; A map with the same format than deps.edn. :deps-map is used to resolve the project resources.
                   :deps-map                    (deps-reader/slurp-deps "deps.edn")
                   ;; Set to true to allow local dependencies and snapshot versions of maven dependencies.
                   :allow-unstable-deps?        true
                   ;; When set to true and resource conflicts are found, then a warning is printed to *err*
                   :warn-on-resource-conflicts? true})
  ;; Recursively walk the bundle files and delete all the Clojure source files
  (uberjar/walk-directory
    out-path
    (fn [dir f] (when (or (.endsWith (str f) ".clj"))
                  (java.nio.file.Files/delete f))))
  (java.nio.file.Files/delete (.resolve out-path "META-INF/BC1024KE.DSA"))
  (java.nio.file.Files/delete (.resolve out-path "META-INF/BC1024KE.SF"))
  (java.nio.file.Files/delete (.resolve out-path "META-INF/BC2048KE.DSA"))
  (java.nio.file.Files/delete (.resolve out-path "META-INF/BC2048KE.SF"))
  ;; Output a MANIFEST.MF file defining 'badigeon.main as the main namespace
  (spit (str (badigeon.utils/make-path out-path "META-INF/MANIFEST.MF"))
        (jar/make-manifest 'bastel-proxy.main))
  ;; Return the paths of all the resource conflicts (multiple resources with the same path) found on the classpath.
  (uberjar/find-resource-conflicts {:deps-map (deps-reader/slurp-deps "deps.edn")})
  ;; Zip the bundle into an uberjar
  (zip/zip out-path jar-name)
  (println "Uberjar built. Result: " jar-name))

(defn copy-to-dist [source]
  (let [src-file (io/file source)
        dest-file (io/file (str "./target/bastel-proxy/" (.getName src-file)))]
    (io/copy src-file dest-file)))

(defn sh-check [& args]
  (let [r (apply sh/sh args)]
    (when (not= 0 (:exit r))
      (println "Failed to run " args r))))

(defn dist []
  (uberjar)
  (.mkdirs (io/file "./target/bastel-proxy"))
  (copy-to-dist jar-name)
  (copy-to-dist "./build/bastel-proxy.sh")
  (copy-to-dist "./build/bastel-proxy.bat")
  (copy-to-dist "./config.edn")
  (copy-to-dist "./README.md")
  (copy-to-dist "./LICENSE.txt")
  (copy-to-dist "./install-root-cert.sh")
  (copy-to-dist "./iptable-install.sh")
  (copy-to-dist "./iptable-uninstall.sh")
  (sh-check "chmod" "+x" "./target/bastel-proxy/bastel-proxy.sh")
  (sh-check "tar" "czfv" final-dist-tar "./bastel-proxy" :dir "./target/")
  (zip/zip (.toPath (io/file "./target/bastel-proxy")) final-dist-zip)
  (println "Distribution build. Result: " final-dist-tar)
  (println "Distribution build. Result: " final-dist-zip))

(defn -main
  ([]
   (println "Using default command: javac. Available commands: javac, uberjar")
   (javac)
   (shutdown-agents))
  ([command]
   (condp = command
     "javac" (javac)
     "uberjar" (uberjar)
     "dist" (dist)
     (println "Unknown command:" command))
   (shutdown-agents)))