(ns bastel-proxy.hosts
  (:require [clojure.string :as str]
            [bastel-proxy.unix-sudo :as u]
            [bastel-proxy.misc :as m]
            [clojure.string :as s])
  (:import (java.io File)))

(def line-separator (System/lineSeparator))

; Rebindable for testing purpose
(def ^:dynamic *binding-marker* "Bastel-Proxy-entries")
(defn hosts-start-marker [] (str "#start " *binding-marker*))
(defn hosts-end-marker [] (str "#end " *binding-marker*))

(defn read-hosts []
  (if m/is-windows
    (slurp "C:\\Windows\\system32\\drivers\\etc\\hosts")
    (slurp "/etc/hosts")))

(defn create-new-host-file
  "Creates a new hosts file with the specified entries.
  Example: Existing /etc/hosts
    127.0.0.1 my-machine

  Call: (create-new-host-file \"127.0.0.1 example.domain\")
  Creates a file with the content
    127.0.0.1 my-machine

    #start Bastel-Proxyy-entries
    127.0.0.1       example.domain
    #end Bastel-Proxy-entries\n
  "
  [existing host-entries]
  (let [line-sep-len (.length line-separator)
        start-idx (or
                    (some-> (str/index-of existing (hosts-start-marker)) (- line-sep-len))
                    (.length existing))
        end-idx (or
                  (some-> (str/index-of existing (hosts-end-marker))
                          (+ (.length (hosts-end-marker)) line-sep-len))
                  (.length existing))
        start (subs existing 0 start-idx)
        end (subs existing end-idx (.length existing))]
    (str start
         line-separator
         (hosts-start-marker)
         line-separator
         host-entries
         line-separator
         (hosts-end-marker)
         line-separator
         end)))

(defn windows-copy-new-hosts
  "Copies the specified file to C:\\Windows\\system32\\drivers\\etc\\hosts.
  Prompts for admin credentials to do so."
  [hosts-file]
  (let [update-cmds (File/createTempFile "update-hosts" ".bat")]
    (spit update-cmds
          (str/join "\n"
                    ["@echo off"
                     "if not \"%1\"==\"am_admin\" (powershell start -verb runas '%0' am_admin & exit)"
                     "copy /Y C:\\Windows\\system32\\drivers\\etc\\hosts C:\\Windows\\system32\\drivers\\etc\\hosts.bak"
                     (str "copy /Y " (.getCanonicalFile hosts-file) " C:\\Windows\\system32\\drivers\\etc\\hosts")
                     (str "del " (.getCanonicalFile hosts-file))]))
    (apply clojure.java.shell/sh (concat ["cmd" "/c" "start" (.getAbsolutePath update-cmds)]))))

(defn unix-copy-new-hosts
  "Copies the specified file to /etc/hosts
  Promps for sudo credentials to do so."
  [gain-root-config hosts-file]
  (u/sudo gain-root-config "backup /etc/hosts to /etc/hosts.back" ["cp" "/etc/hosts" "/etc/hosts.back"])
  (u/sudo gain-root-config "write updated /etc/hosts" ["cp" (.getCanonicalPath hosts-file) "/etc/hosts"]))


(defn update-hosts-file [gain-root-config hosts-text]
  "Updates the hosts managed by Bastel-Proxy in the /etc/hosts file.
  Other hosts entries are left alone. Prompts for Admin/sudo permissions"
  (let [new-host-file (doto (File/createTempFile "hosts" ".txt") (.deleteOnExit))
        old-entry (read-hosts)
        updated (create-new-host-file (read-hosts) hosts-text)]
    (println old-entry)
    (println "--------")
    (println updated)
    (when (not (= old-entry updated))
      (spit new-host-file updated)
      (if m/is-windows
        (windows-copy-new-hosts new-host-file)
        (unix-copy-new-hosts gain-root-config new-host-file))
      ; On Windows we launch a separate console windows with UAC prompt. We don't know what the users clicks and when it's donw
      ; Therefore the temp file is cleaned up later by the script.
      (when (not m/is-windows)
        (.delete new-host-file)))))

(defn host-entries
  "Points the list of hosts to the ip, for the /etc/hosts file.
  host-entries([my.domain sub.domain] 127.0.0.1) returns
  127.0.0.1  my.domain
  127.0.0.1  sub.domain"
  [hosts ip]
  (let [hosts (distinct hosts)
        host-lines (map (fn [h] (str ip "\t" h)) hosts)
        mapping (s/join line-separator host-lines)]
    mapping))

(defn update-hosts [gain-root-config hosts]
  "Creates entries for the hosts pointing to 127.0.0.1 in /etc/hosts.
  Prompts for Admin/sudo permissions"
  (update-hosts-file gain-root-config (host-entries hosts "127.0.0.1")))