(ns bastel-proxy.config_test
  (:use [bastel-proxy.config])
  (:require [clojure.test :as t]))

(t/deftest remove-unbalanced-port-config
  (t/is (= defaults (merge-defaults {})))
  (t/is (= 81 (-> (merge-defaults {:ports {:http {:port 81}}}) :ports :http :port)))
  (t/is (= 8080 (-> (merge-defaults {:ports {:http {:port 81}}}) :ports :http :low-privilige-port))))