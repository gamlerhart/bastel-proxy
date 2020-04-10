(ns bastel-proxy.e2e-test
  (:require [bastel-proxy.unix-sudo :as u]
            [bastel-proxy.config :as c]
            [bastel-proxy.proxy-server :as s]
            [clojure.test :as t]
            [clj-http.client :as http]
            [bastel-proxy.hosts :as h])
  (:import (java.net ServerSocket InetSocketAddress)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def run-e2e? (= "enable" (System/getProperty "e2e.test")))

(defn enable-e2e-tests []
  (System/setProperty "e2e.test" "enable"))

(defn free-port []
  (with-open [ss (ServerSocket.)]
    (.bind ss (InetSocketAddress. "127.0.0.1" 0))
    (.getLocalPort ss)))


(defn create-test-dir []
  (let [dir-attr (into-array FileAttribute [])
        test-dir (Files/createTempDirectory "bastel-proxy-test-files" dir-attr)
        subdir (Files/createDirectory (.resolve test-dir "subdir") dir-attr)]
    (spit (.toFile (.resolve test-dir "file.txt")) "Example")
    (spit (.toFile (.resolve subdir "subdir-file.txt")) "Subdir-Example")
    (str (.toAbsolutePath test-dir))))

(defn delete-recursively [fname]
  (doseq [f (reverse (file-seq (clojure.java.io/file fname)))]
    (clojure.java.io/delete-file f)))

(defn get-body [url]
  (println "GET" url)
  (:body (http/get url)))

(defn run-server-and-test [config]
  (println "End to end test enabled. The tests might prompt you for your root password.")
  (let [root-pwd (u/ask-password (:gain-root config) "Root password to run the end to end test")
        test-dir (create-test-dir)
        http (free-port)
        https (free-port)
        base-config (-> config
                        (assoc-in [:ports :http :port] http)
                        (assoc-in [:ports :https :port] https))
        file-url (str "http://files.bastel-proxy-tests.local:" http)
        proxy-url (str "https://proxy.bastel-proxy-tests.local:" https)
        test-config (c/deep-merge base-config
                                  {:sites
                                   {"files.bastel-proxy-tests.local" {:files test-dir}
                                    "proxy.bastel-proxy-tests.local" {:proxy-destination
                                                                      (str file-url "/subdir")}}})
        _ (println test-config)
        test-server (s/restart-server test-config)]

    (t/is (= "Example" (get-body (str file-url "/file.txt"))))
    (t/is (= "Subdir-Example" (get-body (str file-url "/subdir/subdir-file.txt"))))
    (t/is (= "Subdir-Example" (get-body (str proxy-url "/subdir-file.txt"))))
    (s/stop)
    (delete-recursively test-dir)))

(defn run-tests []
  (binding [h/*binding-marker* "Bastel-Proxy-E2E-Tests"]
    (run-server-and-test c/defaults)
    (h/update-hosts (:gain-root c/defaults) [])))

(if run-e2e?
  (run-tests)
  (println "End to end tests disabled by default. Set the e2e.test property to enabled to run the tests.
  With -De2e.test=enable. The tests will ask for your root password when required"))



