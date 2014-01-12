(ns zentrope-mq.main
  ;;
  ;; This file isn't really any sort of good style. I use it to work
  ;; out ideas or bugs while running in a repl.
  ;;
  (:require
;;    [clojure.stacktrace :refer [print-stack-trace]]
    [clojure.tools.logging :as log]
    [clojure.core.async :refer [thread alts!! chan timeout close!]]
    [zentrope-mq.rabbitmq :as rabbitmq]))

;;-----------------------------------------------------------------------------
;; App Daemon functions

(defn- shutdown-hook
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

;;-----------------------------------------------------------------------------
;; Publish stuff

(defn- test-msg
  []
  (-> {:app "mq-lib" :hostaddr "127.0.0.1" :ts (System/currentTimeMillis)}
      (pr-str)))

(defn- publish-loop!
  [broker control]
  (thread
    (loop []
      (let [[val port] (alts!! [control (timeout 5000)])]
        (when-not (= port control)
          (try
            (let [body (test-msg)]
              (log/info "publish" body)
              (rabbitmq/pub! broker :test-mq "default" "system.status" (.getBytes body)))
            (catch Throwable t
              (log/error "problem with publication" t)))
          (recur))))))

;;-----------------------------------------------------------------------------
;; Consumer stuff

(defn- consume
  [message]
  (log/info "consume" (-> message (.getBody) (String.))))

(defn- start-consumer
  []
  (rabbitmq/subscribe :system-status "default" "system.status" "test.queue" consume)
  :started)

(defn- stop-consumer
  []
  (rabbitmq/unsubscribe :system-status))

;;-----------------------------------------------------------------------------
;; Standalone app

(defn- start
  [this]
  (log/info "Starting mq.main.")
  (rabbitmq/start)
  (rabbitmq/start! (:broker @this))
  (publish-loop! (:broker @this) (:control-ch @this))
  (start-consumer)
  :started)

(defn- stop
  [this]
  (log/info "Stopping mq.main.")
  (when-let [c (:control-ch @this)]
    (close! c)
    (swap! this assoc :control-ch (chan)))
  (stop-consumer)
  (rabbitmq/stop)
  (rabbitmq/stop! (:broker @this)))

;;-----------------------------------------------------------------------------

(defn -main
  [& args]
  (log/info "hello mq test app")
  (let [lock (promise)
        broker (rabbitmq/make-broker)
        app (atom {:control-ch (chan) :broker broker})]
    (shutdown-hook (fn [] (stop app)))
    (shutdown-hook (fn [] (deliver lock :done)))
    (start app)
    (deref lock)
    (System/exit 0)))
