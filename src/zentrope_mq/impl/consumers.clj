(ns zentrope-mq.impl.consumers
  ;;
  ;; ## Rabbit/MQ Consumers
  ;;
  ;; The idea here is to tell this namespace to start consumers
  ;; delegating to your functions, then just let it go. The code
  ;; here will make sure that consumers that die due to rabbitmq
  ;; connection issues will be restarted after a slight pause,
  ;; over and over again as needed.
  ;;
  (:require [clojure.tools.logging :as log :only [debug]]
            [zentrope-mq.impl.conn :as conn])
  (:import [com.rabbitmq.client AlreadyClosedException
                                ShutdownSignalException
                                QueueingConsumer]))

(def ^:private resurrection? (atom false))
(def ^:private live-consumers (ref {}))
(def ^:private dead-consumers (ref {}))

;; Various java-interop flags for constructing a consumer.
(def ^:private durable? false)
(def ^:private auto-delete? true)
(def ^:private exclusive-queue? false)
(def ^:private auto-ack? true)
(def ^:private exchange-type "topic")

(defn- mk-channel
  [consumer]
  (doto (.createChannel (conn/connection))
    (.exchangeDeclare (:exchange consumer) exchange-type durable? auto-delete? nil)
    (.queueDeclare (:queue consumer) durable? exclusive-queue? auto-delete? nil)
    (.queueBind (:queue consumer) (:exchange consumer) (:route consumer))))

(defn- mk-delivery
  [consumer]
  ;;
  ;; According the the new docs for 2.7.0, QueueingConsumer is
  ;; deprecated. Instead, we should extend DefaultConsumer or
  ;; implement Consumer directly.
  ;;
  (let [delivery (QueueingConsumer. (:channel consumer))]
    (doto (:channel consumer)
      (.basicConsume (:queue consumer) auto-ack? delivery))
    (assoc consumer :delivery delivery)))

(defn- mk-consumer
  [consumer]
  (let [c (assoc consumer :channel (mk-channel consumer))]
    (mk-delivery c)))

(defn- consumer-birth
  [consumer]
  (log/debug "birth" (:pid consumer) (:route consumer) (:queue consumer))
  (dosync
   (alter dead-consumers dissoc (:pid consumer))
   (alter live-consumers assoc (:pid consumer) consumer)))

(defn- consumer-death
  [consumer reason]
  (log/debug "death" (:pid consumer) reason)
  (when (instance? AlreadyClosedException reason)
    (log/debug "death:" "now attempting to reset mq connection")
    (conn/reset))
  (let [c (-> consumer
              (dissoc :channel)
              (dissoc :delivery))]
    (dosync
     (alter live-consumers dissoc (:pid c))
     (when @resurrection?
       (alter dead-consumers assoc (:pid c) c)))))

(defn- consume-fn
  [consumer]
  (try
    (loop []
      (let [d (.nextDelivery (:delivery consumer))]
        (try
          ((:delegate consumer) d)
          (catch Throwable t
            (log/debug (:pid consumer) (.getMessage t)))))
      (recur))
    ;;
    ;; An unsubscribe will close the channel, which will cause a shutdown
    ;; exception. Let the consumer have a permanent death at this point unless
    ;; the broker sent the signal.
    ;;
    (catch ShutdownSignalException t
      (if (.isInitiatedByApplication t)
        (log/debug "consumer" (:pid consumer) "shutdown explicitly, not reviving")
        (consumer-death consumer t)))
    ;;
    ;; For all other exceptions, schedule the consumer for ressurection.
    ;;
    (catch Throwable t
      (consumer-death consumer t))))

(defn- start-consumer
  [consumer]
  (log/debug "starting consumer" (:pid consumer))
  (try
    (let [c (mk-consumer consumer)]
      (doto (Thread. (fn [] (consume-fn c)))
        (.setName (str "ztmq.consumer."  (name (:pid c))))
        (.start))
      (consumer-birth c))
    (catch Throwable t
      (consumer-death consumer t))))

;; ----------------------------------------------------------------------------
;; Resurrection monitor
;; ----------------------------------------------------------------------------

(defn- ressurect
  []
  (loop []
    (try
      (doseq [[pid consumer] @dead-consumers]
        (start-consumer consumer))
      (catch Throwable t
        (log/debug "ressurect" (.getMessage t))))
    (Thread/sleep 2000)
    (when @resurrection?
      (recur))))

(defn- start-resurrection-thread
  []
  (doto (Thread. ressurect)
    (.setName "ztmq.consumer.ressurection")
    (.start)))

;; ----------------------------------------------------------------------------
;; Public
;; ----------------------------------------------------------------------------

(defn unsubscribe
  [client-key]
  (try
    (dosync
     (when-let [c (client-key @live-consumers)]
       (alter live-consumers dissoc client-key)
       (alter dead-consumers dissoc client-key)
       (when-let [channel (:channel c)]
         (.close channel))))
    (catch Throwable t
      (log/debug "unsubscribe-error" client-key (.getMessage t)))
    (finally
     [client-key :unsubscribed])))

(defn subscribe
  [client-key exchange route queue delegate]
  (start-consumer {:pid client-key
                   :exchange exchange
                   :route route
                   :queue queue
                   :delegate delegate
                   :channel nil
                   :delivery nil})
  client-key)

(defn start
  []
  (reset! resurrection? true)
  (start-resurrection-thread)
  :started)

(defn stop
  []
  (reset! resurrection? false)
  (doseq [consumer (keys @live-consumers)]
    (unsubscribe consumer))
  :stopped)
