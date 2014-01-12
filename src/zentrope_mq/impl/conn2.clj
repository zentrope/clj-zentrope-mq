(ns zentrope-mq.impl.conn2
  (:import
    [com.rabbitmq.client ConnectionFactory MessageProperties])
  (:require
    [clojure.tools.logging :as log]
    [clojure.string :as string]
    [clojure.core.async :as async]))

;;-----------------------------------------------------------------------------

(def ^:const ^:private non-durable false)
(def ^:const ^:private auto-delete-exchange-off false)
(def ^:const ^:private topic-exchange "topic")

(defn- new-conn!
  [host port]
  (try
    (.newConnection (doto (ConnectionFactory.)
                      (.setHost host)
                      (.setPort port)))
    (catch Throwable t
      (log/error "Unable to make connection: " t)
      nil)))

(defn- close-conn!
  [conn]
  (try
    (.close conn)
    (catch Throwable t
      (log/error "closing conn" t))
    (finally nil)))

;;-----------------------------------------------------------------------------

(defn make-channel!
  [this exchange]
  (doto (.createChannel (:conn @this))
    (.exchangeDeclare exchange topic-exchange non-durable auto-delete-exchange-off nil)))

(defn close-channel!
  [this channel]
  (try
    (.close channel)
    (catch Throwable t
      (log/error "failed to close channel" t))))

(defn basic-publish!
  [this channel exchange route data]
  (try
    (.basicPublish channel exchange route (MessageProperties/TEXT_PLAIN) data)
    :okay
    (catch Throwable t
      (log/error "unable to publish:" t)
      nil)))

(defn open?
  [this]
  (let [conn (:conn @this)]
    (cond
      (nil? conn) false
      (.isOpen conn) true
      :else false)))

(defn make
  [host port]
  (atom {:addr [host (Integer/parseInt port)]
         :conn nil}))

(defn start!
  [this]
  (let [[host port] (:addr @this)]
    (swap! this assoc :conn (new-conn! host port)))
  :started)

(defn restart!
  [this]
  (log/info "restarting connection")
  (let [[host port] (:addr @this)]
    (swap! this assoc :conn (new-conn! host port))))


(defn stop!
  [this]
  (when-let [c (:conn @this)]
    (close-conn! c)
    (swap! this assoc :conn nil))
  :stopped)
