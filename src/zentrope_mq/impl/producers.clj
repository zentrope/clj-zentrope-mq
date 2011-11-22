(ns zentrope-mq.impl.producers
  (:import [com.rabbitmq.client MessageProperties AlreadyClosedException])
  (:require [zentrope-mq.impl.conn :as conn]
            [clojure.tools.logging :as log]))

(def ^:private publishers (atom {}))

(defn- publish-error
  "When a message send applied by an agent fails, remove the agent
from the global publishers list. It'll be recreated on the next attempt
to send a message."
  [agent exception]
  (log/error "agent-error" agent "exception" exception)
  (swap! publishers dissoc (:pid agent))
  (when (instance? AlreadyClosedException exception)
    (log/warn "producers" "attempting to reset a closed connection")
    (conn/reset)))

(defn- assoc-channel
  "Associate a channel with the producer."
  [producer]
  (assoc producer :channel
         (doto (.createChannel (conn/connection))
           (.exchangeDeclare (:exchange producer)
                             "topic" false true nil))))

(defn- mk-and-register-agent
  "Return an agent suitable for handling message sends and
register it with the global list of publishers."
  [producer]
  (let [p (assoc-channel producer)
        a (agent p :handler-fn publish-error)]
    (swap! publishers assoc (:pid p) a)
    a))

(defn- find-agent
  "Return an agent matching the producer spec, or create one if
it doesn't already exist."
  [producer]
  (if-let [producer-agent ((:pid producer) publishers)]
    producer-agent
    (mk-and-register-agent producer)))

(defn- basic-publish
  "Send a message to a publisher's channel (called from within a function
applied by an agent) and returns the publisher."
  [publisher data]
  (.basicPublish (:channel publisher)
                 (:exchange publisher)
                 (:route publisher)
                 (MessageProperties/TEXT_PLAIN)
                 data)
  publisher)

(defn- send-to
  "Send data to the aysnchronous agent representing the channel
for the exchange/route implied by the publisher."
  [producer data]
  (send-off (find-agent producer) (fn [p] (basic-publish p data))))

;; ----------------------------------------------------------------------------

(defn publish
  [pid exchange route data]
  (send-to {:pid pid
            :exchange exchange
            :route route} data))

(defn start
  []
  :started)

(defn stop ;; clear?
  []
  (reset! publishers {})
  ;; iterate through any existing producers and call close on
  ;; the channel?
  :stopped)
