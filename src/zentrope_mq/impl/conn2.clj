(ns zentrope-mq.impl.conn2
  (:import
    [com.rabbitmq.client ConnectionFactory])
  (:require
    [clojure.tools.logging :as log]
    [clojure.string :as string]
    [clojure.core.async :as async]))

;;-----------------------------------------------------------------------------

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

(defn make
  [host port]
  (atom {:addr [host port]
         :conn nil}))

(defn start!
  [this]
  (let [[host port] (:addr @this)]
    (swap! this assoc :conn (new-conn! host port)))
  :started)

(defn stop!
  [this]
  (when-let [c (:conn @this)]
    (close-conn! c)
    (swap! this assoc :conn nil))
  :stopped)
