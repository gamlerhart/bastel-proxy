(ns bastel-proxy.unix-sudo
  (:require [clojure.string :as str]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [bastel-proxy.misc :as m])
  (:import (java.io File IOException)))

(def install-ip-tables-script (.getCanonicalPath (File. "." "iptable-install.sh")))
(def uninstall-ip-tables-script (.getCanonicalPath (File. "." "iptable-uninstall.sh")))
(def ask-password-attempts 3)

(defn password->stdin [password]
  (if (empty? password)
    password
    (str password m/new-line)))
(defn password-valid? [gain-root-config password]
  (if (:ask-password gain-root-config)
    (let [pwd-std (password->stdin password)
          command (concat (:exec gain-root-config) ["id" :in pwd-std])
          result (apply sh/sh command)]
      (= 0 (:exit result)))
    true))


(defn ask-password [gain-root-config purpose]
  (if (or (password-valid? gain-root-config "") (not (:ask-password gain-root-config)))
    ""
    (loop [i 0
           password ""]
      (if (password-valid? gain-root-config password)
        password
        (if (< i ask-password-attempts)
          (do
            (println
              "Please enter your password to"
              purpose
              (str "(Attempt " (inc i) " of " ask-password-attempts ")"))
            (recur (inc i) (read-line)))
          password)))))

(defn has-iptables []
  (try
    (do (sh/sh "iptables")
        true)
    (catch IOException e false)))

(defn sudo [gain-root-config purpose cmd]
  (let [password (ask-password gain-root-config purpose)
        cmd (concat (:exec gain-root-config)
                     cmd
                     [:in (password->stdin password)])
        result (apply sh/sh cmd)]
    (m/print-sh-out result)
    result))

(defn- run-ip-tables-script
  ([script config]
   (run-ip-tables-script script (:ports config) (:gain-root config)))
   ([script ports gain-root-config]
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