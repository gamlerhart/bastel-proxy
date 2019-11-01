(ns bastel-proxy.config
  (:require [clojure.java.io :as io])
  (:import (java.io IOException)))

(def defaults
  {:ports     {:http     {
                          :port               80
                          :low-privilege-port 42380
                          }
               :https    {
                          :port               443
                          :low-privilege-port 42381
                          }
               :iptables true}
   :gain-root {:exec         ["sudo" "--stdin"]
               :ask-password true}
   :sites     {}
   })

(defn- deep-merge
  "Recursively merge two clojure mags"
  [a b]
  (merge-with (fn [x y]
                (if (map? y)
                  (deep-merge x y)
                  y))
              a b))


(defn read-config
  ([fallback]
   (try
     (let [config (load-file "./config.edn")]
       (deep-merge defaults config))
     (catch Exception e
       (println "Failed to read ./config.edn file. Error:" (.getMessage e) "Using fallback-config")
       (prn fallback)
       fallback
       )))
  ([] (read-config defaults)))

(defn config-mod-date []
  (.lastModified (io/file "./config.edn")))