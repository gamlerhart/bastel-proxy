(ns bastel-proxy.hosts-test
  (:require [bastel-proxy.hosts :as h]
            [clojure.test :as t]
            [clojure.string :as str]))

(def empty-host-file "")
(def with-some-hosts "127.0.0.1       localhost
127.0.1.1       gamlor-t470p
::1     localhost ip6-localhost ip6-loopback
ff02::1 ip6-allnodes
ff02::2 ip6-allrouters

# Existing comment
")

(def example-hosts "127.0.0.1\tmy.host\n127.0.0.1\tother.host.ch")
(def example-hosts-2 "127.0.0.2\tsecond.host")

(t/deftest create-host-entries2
  (t/are [hosts expected] (= (h/host-entries hosts "127.0.0.1") expected)
                          [] ""
                          ["my.host"] "127.0.0.1\tmy.host"
                          ["my.host" "other.host.ch"] "127.0.0.1\tmy.host\n127.0.0.1\tother.host.ch")
  (t/is (= "127.0.0.2\tmy.host" (h/host-entries ["my.host"] "127.0.0.2"))))

(t/deftest updates-host-file
  (t/is (= (h/create-new-host-file empty-host-file example-hosts) "\n#start Bastel-Proxy-entries\n127.0.0.1\tmy.host\n127.0.0.1\tother.host.ch\n#end Bastel-Proxy-entries\n"))
  (t/is (str/includes? (h/create-new-host-file with-some-hosts example-hosts) with-some-hosts)))

(t/deftest two-hosts-definitions
  (let [existing (h/create-new-host-file with-some-hosts example-hosts)]
    (binding [h/*binding-marker* "Other Context"]
      (let [second-update (h/create-new-host-file existing example-hosts-2)]
        (t/is (str/includes? second-update existing))
        (t/is (str/includes? second-update example-hosts-2))
        (t/is (str/includes? second-update "#start Other Context"))))))