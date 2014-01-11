(ns zentrope-mq.impl.producers2
  (:import
    [com.rabbitmq.client MessageProperties AlreadyClosedException])
  (:require
    [zentrope-mq.impl.conn2 :as conn]
    [clojure.tools.logging :as log]
    [clojure.core.async :refer [go-loop <! put! chan close!]]))

;;-----------------------------------------------------------------------------
;; AMQP / RabbitMQ interop

(def ^:const ^:private non-durable false)
(def ^:const ^:private auto-delete-exchange-off false)
(def ^:const ^:private topic-exchange "topic")

(defn- make-channel!
  [conn exchange]
  (doto (.createChannel conn)
    (.exchangeDeclare exchange topic-exchange non-durable auto-delete-exchange-off nil)))

(defn- basic-publish!
  [channel exchange route data]
  (.basicPublish channel exchange route (MessageProperties/TEXT_PLAIN) data))

(defn- close-channel!
  [channel]
  (.close channel))

;;-----------------------------------------------------------------------------

(defn- pub-loop!
  [{:keys [channel queue exchange route data] :as producer}]
  (go-loop []
    (when-let [data (<! queue)]
      (try
        (basic-publish! channel exchange route data)
        (catch AlreadyClosedException t
          (log/error "Connection closed: implement reconnect attempt.")
          (throw t))
        (catch Throwable t
          (log/error "Unable to publish: " producer " , " t)
          (throw t)))
      (recur))
    (close-channel! channel)))

(defn- make-producer
  [conn pid exchange route]
  {:pid pid
   :exchange exchange
   :route route
   :queue (chan)
   :channel (make-channel! conn exchange)})

(defn- find-producer!
  "Find or create a producer matching the params."
  [this pid exchange route]
  (if-let [p (pid (:producers @this))]
    p
    (let [p (make-producer (:conn @this) pid exchange route)]
      (swap! this (fn [s] (update-in s [:producers] assoc pid p)))
      (pub-loop! p)
      p)))

;;-----------------------------------------------------------------------------

(defn publish!
  [this pid exchange route data]
  (let [producer (find-producer! pid exchange route)]
    (put! (:queue producer) data)))

(defn make
  [conn]
  (atom {:conn conn
         :producers {}}))

(defn start!
  [this]
  :started)

(defn stop!
  [this]
  (doseq [p (:producers @this)]
    (close! (:queue p)))
  (swap! this dissoc :producers)
  :stopped)
