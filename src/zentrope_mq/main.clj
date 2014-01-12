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
  [rabbit control]
  (thread
    (loop []
      (let [[val port] (alts!! [control (timeout 5000)])]
        (when-not (= port control)
          (try
            (let [body (test-msg)]
              (log/info "publish" body)
              (rabbitmq/pub! rabbit :test-mq "default" "system.status" (.getBytes body)))
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

(defn- make
  [rabbit]
  (atom {:control-ch (chan)
         :mq rabbit}))

(defn- start
  [this]
  (log/info "Starting mq.main.")
  (rabbitmq/start)
  (rabbitmq/start! (:mq @this))
  (publish-loop! (:mq @this) (:control-ch @this))
  (start-consumer)
  :started)

(defn- stop
  [this]
  (log/info "Stopping mq.main.")
  (when-let [c (:control-ch @this)]
    (close! c)
    (swap! this assoc :control-ch (chan)))
  (stop-consumer)
  (rabbitmq/stop))

;;-----------------------------------------------------------------------------

(defn -main
  [& args]
  (log/info "hello mq test app")
  (let [lock (promise)
        rabbit (rabbitmq/make)
        app (make rabbit)]
    (shutdown-hook (fn [] (stop app)))
    (shutdown-hook (fn [] (deliver lock :done)))
    (start app)
    (deref lock)
    (System/exit 0)))
