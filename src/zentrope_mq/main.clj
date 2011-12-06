(ns zentrope-mq.main
  ;;
  ;; This file isn't really any sort of good style. I use it to work
  ;; out ideas or bugs while running in a repl.
  ;;
  (:import [java.util.concurrent Executors TimeUnit])
  (:require [zentrope-mq.core :as mq]
            [clojure.tools.logging :as log]))

(def ^:private exchange "default")

;; ----------------------------------------------------------------------------
;; App Daemon functions
;; ----------------------------------------------------------------------------

(let [lock (promise)]
  (defn- acquire-lock [] (deref lock))
  (defn- release-lock [] (deliver lock 'done)))

(defn- shutdown-hook
  [thread-name runnable]
  (.addShutdownHook (Runtime/getRuntime)
                    (doto (Thread. runnable)
                      (.setName thread-name))))

;; ----------------------------------------------------------------------------
;; Thread convenience functions
;; ----------------------------------------------------------------------------

(def ^:private threads (atom {}))

(defn- run-daemon
  [pid runnable interval]
  (let [e (Executors/newScheduledThreadPool 1)
        t (.scheduleAtFixedRate e runnable interval interval TimeUnit/SECONDS)]
    (swap! threads assoc pid e)))

(defn- stop-daemon
  [pid]
  (when-let [e (pid @threads)]
    (.shutdownNow e)
    (swap! threads dissoc pid)))

;; ----------------------------------------------------------------------------
;; Publish stuff
;; ----------------------------------------------------------------------------

(def ^:private send-it (partial mq/publish :test-mq exchange "system.status"))

(def ^:private test-msg (.getBytes "{\"app\" : \"mq-lib\",\"hostaddr\" : \"127.0.0.1\"}"))

(defn- publish
  []
  (try
    (do
      (log/info "sending status")
      (send-it test-msg))
    (catch Throwable t
      (log/error t))))

(defn- start-publisher
  []
  (run-daemon :stat-pub publish 5))

(defn- stop-publisher
  []
  (stop-daemon :stat-pub))


;; ----------------------------------------------------------------------------
;; Consumer stuff
;; ----------------------------------------------------------------------------

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

;; ----------------------------------------------------------------------------
;; Standalone app
;; ----------------------------------------------------------------------------

(defn- start
  []
  (log/info "Starting mq.main.")
  (mq/start)
  (start-consumer)
  (start-publisher)
  :started)

(defn- stop
  []
  (log/info "Stopping mq.main.")
  (stop-publisher)
  (stop-consumer)
  (mq/stop))

;; ----------------------------------------------------------------------------

(defn -main
  [& args]
  (log/info "Hello MQ Test App")
  (shutdown-hook "mq.main.shutdown" (fn []
                                      (stop)
                                      (release-lock)))
  (start)
  (acquire-lock)
  (log/info "Done."))
