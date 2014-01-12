(ns zentrope-mq.rabbitmq
  (:require
    [clojure.tools.logging :as log]
    ;; new
    [zentrope-mq.impl.amqp :as amqp]
    [zentrope-mq.impl.producers :as producers]
    ;; old
    [zentrope-mq.impl.conn :as conn]
    [zentrope-mq.impl.consumers :as consumers]))

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

(defn start
  []
  (consumers/start)
  (reset! started? true))

(defn stop
  []
  (reset! started? false)
  (consumers/stop)
  (conn/close))

;;-----------------------------------------------------------------------------
;; New stuff

(defn make
  []
  (let [conn (amqp/make "localhost" "5672")]
    (amqp/start! conn)
    (atom {:conn conn :prod (producers/make conn)})))

(defn pub!
  [this pid exchange route data]
  (producers/publish! (:prod @this) pid exchange route data))

(defn start!
  [this]
  (producers/start! (:prod @this)))

(defn stop!
  [this]
  (producers/stop! (:prod @this))
  (amqp/stop! (:conn @this)))
