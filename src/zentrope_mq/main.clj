(ns zentrope-mq.main
  ;;
  ;; This file isn't really any sort of good style. I use it to work
  ;; out ideas or bugs while running in a repl.
  ;;
  (:require
    [clojure.tools.logging :as log]
    [clojure.core.async :refer [thread alts!! chan timeout close!]]
    [zentrope-mq.core :as mq]))

;;-----------------------------------------------------------------------------
;; App Daemon functions

(defn- shutdown-hook
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

;;-----------------------------------------------------------------------------
;; Publish stuff

(def ^:private exchange "default")
(def ^:private send-it (partial mq/publish :test-mq exchange "system.status"))

(defn- test-msg
  []
  (-> {:app "mq-lib" :hostaddr "127.0.0.1" :ts (System/currentTimeMillis)}
      (pr-str)
      (.getBytes)))

(defn- publish-loop!
  [control]
  (thread
    (loop []
      (let [[val port] (alts!! [control (timeout 5000)])]
        (when-not (= port control)
          (send-it (test-msg))
          (recur))))))

;;-----------------------------------------------------------------------------
;; Consumer stuff

(defn- consume
  [message]
  (log/info "consume" (-> message (.getBody) (String.))))

(defn- start-consumer
  []
  (mq/subscribe :system-status exchange "system.status" "test.queue" consume)
  :started)

(defn- stop-consumer
  []
  (mq/unsubscribe :system-status))

;;-----------------------------------------------------------------------------
;; Standalone app

(defn- make
  []
  (atom {:control-ch (chan)}))

(defn- start
  [this]
  (log/info "Starting mq.main.")
  (mq/start)
  (publish-loop! (:control-ch @this))
  (start-consumer)
  :started)

(defn- stop
  [this]
  (log/info "Stopping mq.main.")
  (when-let [c (:control-ch @this)]
    (close! c)
    (swap! this assoc :control-ch (chan)))
  (stop-consumer)
  (mq/stop))

;;-----------------------------------------------------------------------------

(defn -main
  [& args]
  (log/info "hello mq test app")
  (let [lock (promise)
        app (make)]
    (shutdown-hook (fn [] (stop app)))
    (shutdown-hook (fn [] (deliver lock :done)))
    (start app)
    (deref lock)
    (System/exit 0)))
