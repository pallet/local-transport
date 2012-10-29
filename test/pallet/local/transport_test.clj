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
  ;; this fails for ssh for some reason
  ;; (testing "Explicit shell"
  ;;   (let [result (transport/exec
  ;;                 t-state
  ;;                 {:execv ["/bin/bash"] :in "ls /; exit $?"} nil)]
  ;;     (is (zero? (:exit result)))
  ;;     (is (re-find #"bin" (:out result)))))
  (testing "Explicit program"
    (let [result (transport/exec {:execv ["/bin/ls" "/"]} nil)]
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
      (is (not (zero? (:exit result)))))))

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
