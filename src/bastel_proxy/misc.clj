(ns bastel-proxy.misc
  (:require [clojure.string :as str]))

(def is-windows (str/includes? (str/lower-case (System/getProperty "os.name")) "win"))
(def new-line (System/getProperty "line.separator"))

(defn print-sh-out
  "Print the output for clojure.java.shell/sh to console"
  [result]
  (.append *out* (:out result))
  (.append *out* new-line)
  (when (not-empty (:err result))
    (.append *err* (:err result))
    (.append *err* new-line))
  (when (not= 0 (:exit result))
    (.append *err* (str "Failed to execute. Exit code " (:exit result) new-line))))