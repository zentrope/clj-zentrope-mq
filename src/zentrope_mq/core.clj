(ns zentrope-mq.core
  (:require [zentrope-mq.impl.conn :as conn]
            [zentrope-mq.impl.consumers :as consumers]
            [zentrope-mq.impl.producers :as producers]))

(def ^:private started? (atom false))

(defn- check-started
  []
  (when-not @started?
    (throw (Exception. "mq-not-started"))))

(defn subscribe
  [pid exchange route qname delegate-fn]
  (check-started)
  (consumers/subscribe pid exchange route qname delegate-fn))

(defn unsubscribe
  [pid]
  (check-started)
  (consumers/unsubscribe pid))

(defn publish
  [pid exchange route data]
  (check-started)
  (producers/publish pid exchange route data))

(defn start
  []
  (consumers/start)
  (producers/start)
  (reset! started? true))

(defn stop
  []
  (reset! started? false)
  (consumers/stop)
  (producers/stop)
  (conn/close))

;; Is it wise to shutdown agents?
;;(shutdown-agents)
