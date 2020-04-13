(ns bastel-proxy.proxy-server
  (:require [clojure.string :as str]
            [bastel-proxy.certs :as crt]
            [bastel-proxy.unix-sudo :as u]
            [bastel-proxy.hosts :as h]
            [clojure.java.io :as io]
            [bastel-proxy.misc :as m]
            [ring.util.servlet]
            [bastel_proxy.servlet_request])
  (:import (java.net InetAddress UnknownHostException SocketException ServerSocket SocketAddress InetSocketAddress Socket URI)
           (org.eclipse.jetty.server.handler HandlerCollection)
           (org.eclipse.jetty.servlet DefaultServlet ServletHolder ServletContextHandler FilterHolder)
           (org.eclipse.jetty.server ServerConnector Connector Server)
           (bastel_proxy ProxySocket RequestFilter RequestFilter$FilterResult)
           (org.eclipse.jetty.util.ssl SslContextFactory$Server)
           (java.util UUID EnumSet)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (javax.servlet Servlet DispatcherType)))

(defonce last-server (atom nil))

(defn split-domain-path
  "gamlor.info/some/path -> [gamlor.info some/path]"
  [s] (str/split s #"/" 2))

(defn- file-handler
  "Handler serving files from the specified path"
  [path]
  (let [default-servlet (DefaultServlet.)
        holder (new ServletHolder default-servlet)
        params {"resourceBase" (.getCanonicalPath (io/file path))}]
    (.setInitParameters holder params)
    holder))

(defn- throw-invalid-format [message invalid-value]
  (throw (Exception. (str message " Got: " invalid-value " Check your config.edn file"))))


(defn- new-proxy
  "HTTP proxy handler:
  destination: destination URL, like http://localhost:8080, http://localhost:8080/my-service
  preserveHost?: Foward the original Host header?
  request-rewrite function to replace the original request
  url-rewrite function rewriting url. string->string"
  [destination preserveHost? url-rewrite]
  (when (not (#{"http" "https"} (.getScheme (URI. destination))))
    (throw-invalid-format "Unsupported site configuration. :proxy-destination needs to http or https. " destination))
  (let [forwarder (ProxySocket. url-rewrite)
        holder (new ServletHolder forwarder)
        params (if preserveHost?
                 {"proxyTo" destination "preserveHost" "true"}
                 {"proxyTo" destination})]
    (.setInitParameters holder params)
    holder))

(defn no-op-request-filter [request]
  nil)

(defonce global-request-filter (atom no-op-request-filter))

(defn intercept-requests
  "Start intercepting requests with the specified function. Function takes a request in Ring format,
  return nil to not alter any behavior, return a {:request {ring-request-map}} to change the request
  or return a {:response {ring-response-map}} to handle the request completely"
  [fn] (reset! global-request-filter fn))


(defn stop-intercepting-requests
  "Stop intercepting requests and let the pass"
  []
  (reset! global-request-filter no-op-request-filter))

(defn request-filter-for-java [^HttpServletRequest request ^HttpServletResponse response]
  (let [ring-request (ring.util.servlet/build-request-map request)
        filtered (@global-request-filter ring-request)
        filtered-req (bastel_proxy.servlet_request/replace-request (:request filtered) request)
        filtered-response (if (:response filtered)
                            (do
                              (ring.util.servlet/update-servlet-response response (:response filtered))
                              true)
                            false
                            )
        java-filtered (RequestFilter$FilterResult. filtered-req filtered-response)]
    java-filtered))

(def global-request-filter-obj (RequestFilter. request-filter-for-java))

(defn- handler [site-config]
  (cond
    (:files site-config) (file-handler (:files site-config))
    (:proxy-destination site-config) (new-proxy
                                  (:proxy-destination site-config)
                                  (get site-config :preserve-host false)
                                  (get site-config :url-rewrite identity))
    :else (throw-invalid-format "Unsupported site configuration. Expect a :files or :proxy-destination. " site-config)))

(defn- sites->jetty-handlers
  "Converts the sites into a jetty handler collection"
  [sites-config]
  (let [handler-collection (new HandlerCollection)
        ; Ensure the more specific prefixes go first
        sort-by-longer-prefix (sort-by (fn [e] (count (str/split (first e) #"/"))) > sites-config)]
    (doseq [site-config sort-by-longer-prefix]
      (let [[site site-config] site-config
            [domain path] (split-domain-path site)
            ^Servlet forwarder (handler site-config)
            handler (ServletContextHandler. handler-collection (str "/" path))]
        (when domain
          (do
            (.setVirtualHosts handler (into-array String [domain]))
            (try (InetAddress/getByName domain)
                 (catch UnknownHostException e
                   (println
                     "ERROR: Cannot resolve "
                     domain
                     "Check your host file if this hostname points to your localhost")))))
        (.addFilter handler (FilterHolder. global-request-filter-obj) "/*" (EnumSet/of DispatcherType/REQUEST))
        (.addServlet handler forwarder "/*")
        handler))
    handler-collection))

(defn- ssl-context
  "Creates a SSLContext for HTTPS using the specified key-store"
  [key-store-file]
  (let [ssl (SslContextFactory$Server.)
        excluded-protocols (into [] (.getExcludeProtocols ssl))]
    (doto ssl
      (.setKeyStorePath key-store-file)
      (.setKeyStorePassword crt/default-password)
      (.setKeyManagerPassword crt/default-password)
      (.setExcludeProtocols (into-array String (conj excluded-protocols "TLSv1" "TLSv1.1"))))))

(defn- http-connection
  "Handling http on specified port"
  [server port]
  (let [connector (new ServerConnector server)]
    (.setPort connector port)
    (.setHost connector "127.0.0.1")
    connector))

(defn- https-connection
  "Handling https on specified port"
  [server port key-store-file]
  (let [connector (new ServerConnector
                       server
                       (ssl-context key-store-file))]
    (.setPort connector port)
    (.setHost connector "127.0.0.1")
    connector))

(defn sites-domains [sites]
  (->> sites (map first) (map split-domain-path) (map first)))

(defn build-key-store [domains]
  (crt/create-domains-store "domains-cert.pfx" domains)
  "domains-cert.pfx")

(defn do-start-server
  [config ports]
  (let [sites (:sites config)
        domains (sites-domains sites)
        gain-root-config (:gain-root config)
        _ (h/update-hosts gain-root-config domains)
        key-store-file (build-key-store domains)
        server (new Server)
        handler (sites->jetty-handlers sites)
        http (if-let [port (-> ports :http :port)] (http-connection server port))
        https (if-let [port (-> ports :https :port)] (https-connection server port key-store-file))
        connectors (filter #(not (nil? %)) [http https])
        server (doto server
                 (.setConnectors (into-array Connector connectors))
                 (.setHandler handler))]
    (try
      (.start server)
      (catch Exception e
        (.stop server)
        (throw e)))
    server))

(defn- can-redirect-ports [config]
  (and (:gain-root config) (not m/is-windows) (u/has-iptables) (-> config :ports :iptables)))

(defn- is-port-permission-error [e]
  (let [msg (.getMessage e)
        cause (.getCause e)]
    (or (and msg (.contains msg "Permission"))
      (when cause (is-port-permission-error cause)))))

(defn protocol-info-string [ports-config]
  (if (some-> ports-config :https :port ) "https" "http"))

(defn- port-info [protocol config key using-iptables?]
  (str protocol ": "
       (get-in config [key :port] "-")
       (when using-iptables? (str " redirecting to " (get-in config [key :low-privilege-port] "-")))))

(defn print-info [config using-iptables?]
  (let [ports-config (:ports config)
        protocol (protocol-info-string ports-config)]
    (println "Ports:"
             (port-info "HTTP" ports-config :http using-iptables?)
             (port-info "HTTPS" ports-config :https using-iptables?))
    (println "Hosting sites:")
    (doseq [[url site-config] (:sites config)]
      (println (str protocol "://" url) " -> " site-config))))

(defn is-redirected
  ([src-port dst-port]
   (try
     (let [cookie (str (UUID/randomUUID))
           listener (future
                      (with-open [server-socket (doto (ServerSocket.)
                                                  (.bind (InetSocketAddress. "127.0.0.1" dst-port))
                                                  (.setSoTimeout 100))
                                  socket (.accept server-socket)
                                  in (.getInputStream socket)]
                        (slurp in)))
           client (future
                    (with-open [client (Socket. "127.0.0.1" src-port)]
                      (spit client cookie)))
           cookie-response @listener]
       (= cookie cookie-response))
     (catch Exception e false)))
  ([port-config]
   (is-redirected (:port port-config) (:low-privilege-port port-config))))

(defn do-start-handle-port-issues
  [config]
  (try
    (do
      (let [s (do-start-server config (:ports config))]
        (print-info config false)
        s))
    (catch Exception e
      (if (is-port-permission-error e)
        (if (can-redirect-ports config)
          (let [http-config (-> config :ports :http)
                https-config (-> config :ports :https)]
            (println "Failed to bind" (:port http-config) (:port https-config)  "ports. Will bind to high number port and redirect via iptables")
            (if (and (is-redirected http-config) (is-redirected http-config))
              (println "Redirecting iptables already installed. Skipping installation")
              (u/redirect-ports config))
            (let [s (do-start-server config
                                     {:http {:port (:low-privilege-port http-config)}
                                      :https {:port (:low-privilege-port https-config)}})]
              (print-info config true)
              s))
          (throw e))
        (throw e)))))

(defn stop []
  (swap! last-server
         (fn [s]
           (when s
             (.stop s)
             (println "Stopped Bastel-Proxy")))))

(defn restart-server
  [config]
  (swap! last-server
         (fn [s]
           (when s
             (.stop s)
             (println "Restarting Bastel-Proxy"))
           (do-start-handle-port-issues config))))



(comment
  (intercept-requests
    ; The function is called for each request, using the Ring format https://github.com/ring-clojure/ring
    ; return nil will process the request as is, returning {:response { ring-response-map }} will return the specified response
    ; and returning {:request {ring-request-map}} will use that request to pass through
    (fn [req]
      (cond
        ; Example Add a header to the /admin page request before sending it along.
        (str/starts-with? (:uri req) "/admin") {:request
                                                (assoc-in req [:headers "X-Secret"] "a-secret")}
        ; Example Respond with 403 for all /forbidden pages
        (str/starts-with? (:uri req) "/forbidden") {:response
                                                    {:status 403 :body "nope, forbidden"}}
        ; Other requests pass through
        :else nil)
      )))