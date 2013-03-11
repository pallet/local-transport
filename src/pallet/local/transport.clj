(ns pallet.local.transport
  "Local transport"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.common.context :as context]
   [pallet.shell :as shell])
  (:import
   [java.io InputStream]))

(def
  ^{:doc "Specifies the buffer size used to read the output stream.
    Defaults to 10K"}
  output-buffer-size (atom (* 1024 10)))

(def
  ^{:doc "Specifies the polling period for retrieving command output.
    Defaults to 1000ms."}
  output-poll-period (atom 1000))

(defn read-buffer [^InputStream stream output-f]
  (let [buffer-size @output-buffer-size
        ^bytes bytes (byte-array buffer-size)
        sb (StringBuilder.)]
    {:sb sb
     :reader (fn []
               (when (pos? (.available stream))
                 (let [num-read (.read stream bytes 0 buffer-size)
                       s (String. bytes 0 (int num-read) "UTF-8")]
                   (output-f s)
                   (.append sb s)
                   s)))}))

(defn sh-script
  "Run a script on local machine.

   Command:
     :execv  sequence of command and arguments to be run (default /bin/bash).
     :in     standard input for the process.
   Options:
     :output-f  function to incrementally process output"
  [{:keys [execv in] :as command}
   {:keys [output-f] :as options}]
  (logging/tracef "sh-script %s" command)
  (if output-f
    (try
      (let [{:keys [^InputStream out ^InputStream err ^Process proc]}
            (apply
             shell/sh
             (concat
              (or execv ["/bin/bash"]) ;; TODO generalise
              [:in in :async true]))
            out-reader (read-buffer out output-f)
            err-reader (read-buffer err output-f)
            period @output-poll-period
            read-out (:reader out-reader)
            read-err (:reader err-reader)]
        (with-open [out out err err]
          (while (not (try (.exitValue proc)
                           (catch IllegalThreadStateException _)))
            (Thread/sleep period)
            (read-out)
            (read-err))
          (while (read-out))
          (while (read-err))
          (let [exit (.exitValue proc)]
            {:exit exit
             :out (str (:sb out-reader))
             :err (str (:sb err-reader))}))))
    (apply shell/sh (concat (or execv ["/bin/bash"]) [:in in]))))


(defn send-stream [source destination {:keys [mode]}]
  (context/with-context
    (format "Send to %s" destination) {}
    (io/copy source (io/file destination))
    (when mode
      (shell/sh "chmod" destination mode))))

(defn send-text [source destination {:keys [mode]}]
  (context/with-context
    (format "Send to %s" destination) {}
    (spit (io/file destination) source)
    (when mode
      (shell/sh "chmod" destination mode))))

(defn receive [source destination]
  (context/with-context
    (format "Receive to %s" destination) {}
    (io/copy (io/file source) (io/file destination))))

(defn exec [code options]
  (context/with-context
    "Execute script" {}
    (let [{:keys [exit out err] :as result} (sh-script code options)]
      (if (zero? exit)
        result
        (assoc result
          :error {:message (format
                            "Error executing script :\n :cmd %s\n :out %s\n"
                            code err)
                  :type :pallet-script-excution-error
                  :script-exit exit
                  :script-out out
                  :script-err err})))))
