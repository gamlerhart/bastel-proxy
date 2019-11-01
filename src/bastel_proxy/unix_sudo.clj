(ns bastel-proxy.unix-sudo
  (:require [clojure.string :as str]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io])
  (:import (java.io File IOException)))

(def install-ip-tables-script (.getCanonicalPath (File. "." "iptable-install.sh")))
(def uninstall-ip-tables-script (.getCanonicalPath (File. "." "iptable-uninstall.sh")))
(def ask-password-attempts 3)
(def new-line (System/getProperty "line.separator"))
(defn password-valid? [gain-root-config password]
  (if (:ask-password gain-root-config)
    (let [command (concat (:exec gain-root-config) ["id" :in (str password new-line)])
          result (apply sh/sh command)]
      (= 0 (:exit result)))
    true))


(defn ask-password [gain-root-config purpose]
  (if (or (password-valid? gain-root-config "") (:ask-password gain-root-config))
    (loop [i 0
           password ""]
      (if (password-valid? gain-root-config password)
        password
        (if (< i ask-password-attempts)
          (do
            (println
              "Please enter password to"
              purpose
              (str "(Attempt " (inc i) " of " ask-password-attempts ")"))
            (recur (inc i) (read-line)))
          password)))
    ""))

(defn has-iptables []
  (try
    (do (sh/sh "iptables")
        true)
    (catch IOException e false)))

(defn sudo [gain-root-config purpose cmd]
  (let [password (ask-password gain-root-config purpose)
        cmd (concat (:exec gain-root-config)
                     cmd
                     [:in (str password new-line)])
        result (apply sh/sh cmd)]
    (.append *out* (:out result))
    (.append *out* new-line)
    (when (not-empty (:err result))
      (.append *err* (:err result))
      (.append *err* new-line))
    (when (not= 0 (:exit result))
      (.append *err* (str "Failed to redirect ports. Exit code " (:exit result) new-line)))
    result))

(defn- run-ip-tables-script
  ([script config]
   (run-ip-tables-script script (:ports config) (:gain-root config)))
   ([script ports gain-root-config]
    (println ports)
    (let [http (:http ports)
          http-src (:port http)
          http-dst (:low-privilege-port http)
          https (:https ports)
          https-src (:port https)
          https-dst (:low-privilege-port https)
          redirect-ports-cmd ["bash" script (str http-src) (str http-dst) (str https-src) (str https-dst)]]
      (sudo gain-root-config "redirect privileged ports" redirect-ports-cmd))))

(defn redirect-ports [config]
  (run-ip-tables-script install-ip-tables-script config))

(defn remove-redirected-ports [config]
  (run-ip-tables-script uninstall-ip-tables-script config))