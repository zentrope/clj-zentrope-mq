(ns zentrope-mq.impl.conn
  ;;
  ;; Manages a single connection to Rabbit/MQ. Consumers and
  ;; producers should use this connection to construct channels
  ;; for their needs.
  ;;
  (:require [clojure.tools.logging :as log])
  (:import [com.rabbitmq.client ConnectionFactory]))

;; TODO: Some way to supply connection properties.

(def ^:private host "localhost")
(def ^:private port 5672)
(def ^:private conn (atom nil))

(defn- mk-connection
  []
  (try
    (.newConnection (doto (ConnectionFactory.)
                      (.setHost host)
                      (.setPort port)))
    (catch Throwable t
      (log/error "Unable to get connection" t)
      nil)))

(defn close
  []
  (locking conn
    (when-not (nil? @conn)
      (swap! conn (fn [c]
                  (try
                    (.close c)
                  (catch Throwable t
                    (log/error "closing conn" t))
                  (finally nil)))))))

(defn reset
  []
  (locking conn
    (swap! conn (fn [c]
                  (when-not (.isOpen c)
                    (close)
                    (mk-connection))))))

(defn connection
  []
  (locking conn
    (when (nil? @conn)
      (swap! conn (fn [_] (mk-connection))))
    @conn))
