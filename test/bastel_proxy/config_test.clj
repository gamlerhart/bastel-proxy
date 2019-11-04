(ns bastel-proxy.config_test
  (:use [bastel-proxy.config])
  (:require [clojure.test :as t]))

(t/deftest remove-unbalanced-port-config
  (t/is (= defaults (deep-merge defaults {})))
  (t/is (= 81 (-> (deep-merge defaults {:ports {:http {:port 81}}}) :ports :http :port)))
  (t/is (= 42380 (-> (deep-merge defaults {:ports {:http {:port 81}}}) :ports :http :low-privilege-port))))