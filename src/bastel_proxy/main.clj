(ns bastel-proxy.main
  (:require [bastel-proxy.config :as c]
            [bastel-proxy.proxy-server :as s :refer [intercept-requests stop-intercepting-requests]]
            [bastel-proxy.certs :as crt]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bastel-proxy.unix-sudo :as u]
            [bastel-proxy.misc :as m]
            [clojure.java.shell :as sh]
            [clojure.repl])
  (:gen-class))

(defn configure-logging []
  (println "Logs are in" (.getCanonicalPath (io/file "bastel-proxy.log")))
  (System/setProperty "org.slf4j.simpleLogger.logFile" "bastel-proxy.log"))

(configure-logging)

(defn powershell [script]
  ; Strip 'File was downloaded from internet' marker blocking execution
  ; When the zip file is downloaded and unpacked by the Windows built in tool, then the scripts are marked as internet downloaded.
  (let [result (sh/sh "powershell.exe" "-NoProfile" "-ExecutionPolicy" "Unrestricted" "-Command"
                      (str "Unblock-File \"" (.getCanonicalPath (io/file script)) "\""))]
    (m/print-sh-out result))
  (let [result (sh/sh "powershell.exe" "-NoProfile" "-ExecutionPolicy" "Unrestricted" "-file" (.getCanonicalPath (io/file script)))]
    (m/print-sh-out result)))

(defn install-certs [gain-root-config]
  (if m/is-windows
    (powershell "install-root-cert.ps1")
    (u/sudo gain-root-config "install CA to trust stores" ["bash" "install-root-cert.sh" (System/getProperty "user.home")])))

(defn install-ca-cert
  ([gain-root-config]
   "Installs the Bastel-Proxy root CA certificate into known trust stores"
   (let [store (crt/load-or-create-root)]
     (crt/export-cert (crt/root-cert store) "root-cert.crt"))
   (install-certs gain-root-config)
   (println "Installed Bastel-Proxy root-cert.crt certificate in known CA locations."))
  ([] (install-ca-cert (:gain-root (c/read-config)))))

(defn first-time-setup [gain-root-config]
  (when (not (.exists (io/file "./root-certs.pfx")))
    (println "Root certificates not yet installed. The certificates are installed into the trust store of the System, Chrome, and Firefox.")
    (println "You can repeat this step later by starting Bastel Proxy with the --install-ca-cert flag")
    (when m/is-windows
      (println "You have to confirm the evaluation dialog and the intial certificate import"))
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
Press enter to stop watching.")
  (println '(restart) "Restart the Bastel-Proxy")
  (println '(stop) "Stop the Bastel-Proxy")
  (println '(install-ca-cert) "Installs the Bastel-Proxy root CA into known trust stores")
  (println '(iptables-uninstall) "Uninstall the Bastel Proxy iptables. IP tables are cleared on reboot")
  (println '(intercept-requests) "Install a request filter for all requests passing through the proxy")
  (println '(stop-intercepting-requests) "Uninstalls any request filter from the proxy")
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
   (println "No arguments provided, will start proxy and watch configuration. Use --help to see available flags")
   (-main "watch")))