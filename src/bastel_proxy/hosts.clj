(ns bastel-proxy.hosts
  (:require [clojure.string :as str]
            [bastel-proxy.unix-sudo :as u])
  (:import (java.io File)))

(def is-windows (str/includes? (str/lower-case (System/getProperty "os.name")) "win"))
(def line-separator (System/lineSeparator))

(def hosts-start-marker "#start Bastel-Proxy-entries")
(def hosts-end-marker "#end Bastel-Proxy-entries")

(defn read-hosts []
  (if is-windows
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
  [host-entries]
  (let [line-sep-len (.length line-separator)
        existing (read-hosts)
        start-idx (or
                    (some-> (str/index-of existing hosts-start-marker) (- line-sep-len))
                    (.length existing))
        end-idx (or
                  (some-> (str/index-of existing hosts-end-marker) (+ (.length hosts-end-marker) line-sep-len))
                  (.length existing))
        start (subs existing 0 start-idx)
        end (subs existing end-idx (.length existing))]
    (str start
         line-separator
         hosts-start-marker
         line-separator
         host-entries
         line-separator
         hosts-end-marker
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
                     (str "copy /Y " (.getCanonicalFile hosts-file) " C:\\Windows\\system32\\drivers\\etc\\hosts")]))
    (apply clojure.java.shell/sh (concat ["cmd" "/c" "start" (.getAbsolutePath update-cmds)]))))

(defn unix-copy-new-hosts
  "Copies the specified file to /etc/hosts
  Promps for sudo credentials to do so."
  [gain-root-config hosts-file]
  (u/sudo gain-root-config "backup /etc/hosts to /etc/hosts.back" ["cp" "/etc/hosts" "/etc/hosts.back"])
  (u/sudo gain-root-config "write updated /etc/hosts" ["cp" (.getCanonicalPath hosts-file) "/etc/hosts"]))


(defn update-hosts-file [gain-root-config hosts]
  "Updates the hosts managed by Bastel-Proxy in the /etc/hosts file.
  Other hosts entries are left alone. Prompts for Admin/sudo permissions"
  (let [new-host-file (doto (File/createTempFile "hosts" ".txt") (.deleteOnExit))
        old-entry (read-hosts)
        updated (create-new-host-file hosts)]
    (when (not (= old-entry updated))
      (spit new-host-file updated)
      (if is-windows
        (windows-copy-new-hosts new-host-file)
        (unix-copy-new-hosts gain-root-config new-host-file))
      (.delete new-host-file)
      )))

(defn host-entries
  "Points the list of hosts to the ip, for the /etc/hosts file.
  host-entries([my.domain sub.domain] 127.0.0.1) returns
  127.0.0.1  my.domain
  127.0.0.1  sub.domain"
  [hosts ip]
  (let [hosts (distinct hosts)
        mapping (reduce (fn [s h] (str s line-separator ip "\t" h)) "" hosts)]
    mapping))

(defn update-hosts [gain-root-config hosts]
  "Creates entries for the hosts pointing to 127.0.0.1 in /etc/hosts.
  Prompts for Admin/sudo permissions"
  (update-hosts-file gain-root-config (host-entries hosts "127.0.0.1")))