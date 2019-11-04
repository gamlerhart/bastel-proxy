(ns bastel-proxy.main
  (:require [bastel-proxy.config :as c]
            [bastel-proxy.proxy-server :as s]
            [bastel-proxy.certs :as crt]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bastel-proxy.unix-sudo :as u])
  (:import (org.eclipse.jetty.util.log Log)
           (java.io PrintStream)
           (java.nio.file Files Paths)
           (java.nio.charset StandardCharsets))
  (:gen-class))

(defn configure-logging []
  (System/setProperty "org.slf4j.simpleLogger.logFile" "bastel-proxy.log"))

(configure-logging)

(defn install-ca-cert
  ([gain-root-config]
   "Installs the Bastel-Proxy root CA certificate into known trust stores"
   (crt/load-or-create-root)
   (u/sudo gain-root-config "install CA to trust stores" ["bash" "install-root-cert.sh" (System/getProperty "user.home")])
   (println "Installed Bastel-Proxy root-cert.crt certificate in known CA locations."))
  ([] (install-ca-cert (:gain-root (c/read-config)))))

(defn first-time-setup [gain-root-config]
  (when (not (.exists (io/file "./root-certs.pfx")))
    (println "Root certificates not yet installed. The certificates are installed into the trust store of the System, Chrome and Firefox.")
    (println "You can repeat this step later by starting Bastel Proxy with the --install-ca-cert flag")
    (println "Hit enter to continue")
    ; Wait for user to hit enter
    (read-line)
    (install-ca-cert gain-root-config)))


(defn restart []
  (let [config (c/read-config)]
    (s/restart-server config)))

(defn stop []
  (s/stop ))

(defn iptables-uninstall []
  (let [config (c/read-config)]
    (u/remove-redirected-ports config)))

(defn- do-start-watching-config
  "Does not work with nREPL at the moment."
  []
  (loop [last-config-mod-date 0
         restart? false
         old-config (c/read-config)]
    (let [config-mod-date (c/config-mod-date)
          same-config (= last-config-mod-date config-mod-date)
          config (if same-config old-config (c/read-config old-config))]
      (if same-config
        (Thread/sleep 200)
        (do
          (first-time-setup (:gain-root config))
          (if restart?
            (println "Configuration modified. Restarting proxy server")
            (println "Starting proxy server"))
          (s/restart-server config)
          (println "Press enter to stop watching...")))
      (when (not (.ready *in*))
        (recur config-mod-date true config)))))

(defn start-watching-config []
  (do-start-watching-config)
  (println "Stopped watching config. Bastel-Proxy keeps running with current config"))

(defn print-help []
  (println
    "Runs the Bastel-Proxy, proxying HTTP traffic according to the config.edn.
Watches the config.edn file for changes and reloads the changes automatically.
Arguments:
  -h, --help:               Show this help
  --repl:                   Start a Clojure REPL
  --iptables-uninstall:     Installs the Bastel-Proxy root CA into known trust stores
  --install-ca-cert:        Uninstall the Bastel Proxy iptables
    "))

(defn print-repl-help []
  (println "Interactive Clojure REPL. Useful functions:")
  (println '(print-repl-help) "Print this help")
  (println '(start-watching-config) "Start Bastel-Proxy and watch the config.edn for changes.
Restarts and applies and changes to config.edn file.
Press enter to stop watching config.edn")
  (println '(restart) "Restart the Bastel-Proxy")
  (println '(stop) "Stop the Bastel-Proxy")
  (println '(install-ca-cert) "Installs the Bastel-Proxy root CA into known trust stores")
  (println '(iptables-uninstall) "Uninstall the Bastel Proxy iptables. IP tables are cleared on reboot")
  )

(defn start-repl []
  (clojure.main/repl :init (fn []
                             (print-repl-help)
                             (in-ns 'bastel-proxy.main))))

(defn -main
  ([arg]
   (condp = (str/lower-case arg)
     "--help" (print-help)
     "-h" (print-help)
     "help" (print-help)
     "--repl" (start-repl)
     "--iptables-uninstall" (iptables-uninstall)
     "--install-ca-cert" (install-ca-cert)
     "watch" (do
               (do-start-watching-config)
               (stop))
     (print-help))
   (shutdown-agents))
  ([]
   (configure-logging)
   (-main "watch")))