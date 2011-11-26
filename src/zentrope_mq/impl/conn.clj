;; ## CONN
;;
;; Manages a single connection to Rabbit/MQ. Consumers and
;; producers should use this connection to construct channels
;; for their needs.
;;
(ns zentrope-mq.impl.conn
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string])
  (:import [com.rabbitmq.client ConnectionFactory]))

(def ^:private addr-props (or (System/getProperty "rabbit.servers")
                              (System/getProperty "rabbitmq.servers")
                              (System/getenv "RABBITMQ_SERVERS")
                              "localhost:5672"))

(def ^:private addr-pairs (string/split addr-props #","))
(def ^:private addresses (ref (cycle addr-pairs)))

(defn- next-addr
  []
  (dosync
   (let [[addr port] (string/split (first @addresses) #":")]
     (alter addresses next)
     [addr (Integer/parseInt port)])))

(def ^:private conn (atom nil))

(defn- mk-connection
  []
  (try
    (let [[host port] (next-addr)]
      (log/info "new-connection" {:host host :port port})
      (.newConnection (doto (ConnectionFactory.)
                        (.setHost host)
                        (.setPort port))))
    (catch Throwable t
      (log/error "Unable to get connection" t)
      nil)))

;; Public

;; Should there be a way to set properties via an explicit function all?

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
