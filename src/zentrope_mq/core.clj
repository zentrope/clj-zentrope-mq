(ns zentrope-mq.core
  (:require [zentrope-mq.impl
             [conn :as conn]
             [consumers :as consumers]
             [producers :as producers]]))

(def ^:private started? (atom false))


(defn- check-started
  ;; NOTE: Should this be used to automatically start monitor
  ;; threads the first time subscribe/publish get called, and
  ;; set up a shutdown hook to call stop.
  []
  (when-not @started?
    (throw (Exception. "mq-not-started"))))

;; ## Public API

(defn subscribe
  "Register a DELEGATE-FN for a specific EXCHANGE, ROUTE, QNAME, using
   a PID as an identifier. Multiple requests with the same PID will
   replace previous registrations with PID. Multiple QNAMEs with
   different PIDs will provide round-robin message delivery."
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
