(ns zentrope-mq.impl.producers
  (:import [com.rabbitmq.client MessageProperties AlreadyClosedException])
  (:require [zentrope-mq.impl.conn :as conn]
            [clojure.tools.logging :as log]))

;; Maintain a list of producers in a map so we can look it up
;; when we want to send it something. On errors, we'll remove it
;; from this list. The next publish request will lazily create a
;; new publisher and add it back here.

(def ^:private publishers (atom {}))

;; A handler error function removes the agent from the map of producers
;; and resets the connection if necessary.

(defn- publish-error
  [agent exception]
  (log/error "agent-error" agent "exception" exception)
  (swap! publishers dissoc (:pid agent))
  (when (instance? AlreadyClosedException exception)
    (log/warn "producers" "attempting to reset a closed connection")
    (conn/reset)))

;; A producer is an Agent containing a map of all the stuff it
;; needs to use to send a message.

(defn- mk-channel
  [producer]
  (assoc producer :channel
         (doto (.createChannel (conn/connection))
           (.exchangeDeclare (:exchange producer)
                             "topic" false true nil))))

(defn- mk-producer
  [producer]
  (let [p (mk-channel producer)
        a (agent p :handler-fn publish-error)]
    (swap! publishers assoc (:pid p) a)
    a))

(defn- find-producer
  [producer]
  (let [p ((:pid producer) publishers)]
    (if (nil? p)
      (mk-producer producer)
      producer)))

(defn- basic-publish
  [publisher data]
  (.basicPublish (:channel publisher)
                 (:exchange publisher)
                 (:route publisher)
                 (MessageProperties/TEXT_PLAIN)
                 data)
  publisher)

(defn- send-to
  [producer data]
  (let [p (find-producer producer)]
    (send-off p (fn [p] (basic-publish p data)))))

(defn publish
  [pid exchange route data]
  (send-to {:pid pid
            :exchange exchange
            :route route} data))

(defn start
  []
  :started)

(defn stop
  []
  (reset! publishers {})
  :stopped)
