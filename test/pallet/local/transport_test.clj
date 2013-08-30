(ns pallet.local.transport-test
  (:require
   [clojure.java.io :as io]
   [pallet.common.filesystem :as filesystem]
   [pallet.common.logging.logutils :as logutils]
   [pallet.local.transport :as transport])
  (:use
   clojure.test))

(use-fixtures :once (logutils/logging-threshold-fixture))

(deftest test-exec
  (testing "Default shell"
    (let [result (transport/exec {:in "ls /; exit $?"} nil)]
      (is (zero? (:exit result)))
      (is (re-find #"bin" (:out result)))))
  (testing "Explicit shell"
    (let [result (transport/exec
                  {:execv ["/bin/bash"] :in "ls /; exit $?"} nil)]
      (is (zero? (:exit result)))
      (is (re-find #"bin" (:out result)))))
  (testing "Explicit program"
    (let [result (transport/exec {:execv ["/bin/ls" "/"]} nil)]
      (is (zero? (:exit result)))
      (is (re-find #"bin" (:out result)))))
  (testing "prefix"
    (let [result (transport/exec
                  {:prefix ["/usr/bin/env"] :execv ["/bin/ls" "/"]}
                  nil)]
      (is (zero? (:exit result)))
      (is (re-find #"bin" (:out result)))))
  (testing "output-f"
    (let [output (atom "")
          result (transport/exec
                  {:execv ["/bin/ls" "/"]}
                  {:output-f (partial swap! output str)})]
      (is (zero? (:exit result)))
      (is (re-find #"bin" (:out result)))
      (is (= (:out result) @output))))
  (testing "Error return"
    (let [result (transport/exec {:in "this-should-fail"} nil)]
      (is (not (zero? (:exit result))))))
  (testing "Env"                        ; if this fails so will the next test
                                        ; as LEIN_VERSION is not in the env
      (let [result (transport/exec
                    {:execv ["/usr/bin/env"]}
                    {:agent-forwarding true})]
        (is (zero? (:exit result)))
        (is (re-find
             (re-pattern (System/getenv "LEIN_VERSION"))
             (:out result)))))
  (testing "Explicit env"
    (let [tmp (java.io.File/createTempFile "pallet_" "tmp")]
      (.deleteOnExit tmp)
      (spit tmp "echo ${XX}${LEIN_VERSION}")
      (try
        (let [result (transport/exec
                      {:prefix ["/usr/bin/sudo"]  ; restrict vars
                       :execv ["/bin/bash" (.getAbsolutePath tmp)]
                       :env-cmd "/usr/bin/env"
                       :env-fwd [:LEIN_VERSION]
                       :env {:XX "abcd"}}
                      nil)]
          (is (zero? (:exit result)))
          (is (re-find
               (re-pattern (str "abcd" (System/getenv "LEIN_VERSION")))
               (:out result))))
        (catch Exception e
          (.delete tmp)
          (throw e))))))

(deftest test-send
  (testing "send-stream"
    (filesystem/with-temp-file [tmp-src "src"]
      (filesystem/with-temp-file [tmp-dest "dest"]
        (transport/send-stream
         (io/input-stream (.getPath tmp-src)) (.getPath tmp-dest) {})
        (is (= "src" (slurp tmp-dest))))))
  (testing "send-stream with mode"
    (filesystem/with-temp-file [tmp-src "src"]
      (filesystem/with-temp-file [tmp-dest "dest"]
        (transport/send-stream
         (io/input-stream (.getPath tmp-src)) (.getPath tmp-dest) {:mode "644"})
        (is (= "src" (slurp tmp-dest))))))
  (testing "send-text"
    (filesystem/with-temp-file [tmp-dest "dest"]
      (transport/send-text "src" (.getPath tmp-dest) {})
      (is (= "src" (slurp tmp-dest)))))
  (testing "receive"
    (filesystem/with-temp-file [tmp-src "src"]
      (filesystem/with-temp-file [tmp-dest "dest"]
        (transport/receive (.getPath tmp-src) (.getPath tmp-dest))
        (is (= "src" (slurp tmp-dest)))))))
