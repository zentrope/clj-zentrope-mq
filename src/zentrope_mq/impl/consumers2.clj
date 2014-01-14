(ns zentrope-mq.impl.consumers
  ;;
  ;; Rewrite
  ;;
  (:require
    [clojure.tools.logging :as log]
    [clojure.core.async :refer [chan thread <! close! filter<]]
    [zentrope-mq.impl.amqp :as amqp])
  ;;
  (:import
    [com.rabbitmq.client QueingConsumer AlreadyClosedException ShutdownSignalException]))

;;-----------------------------------------------------------------------------
;; Move this stuff to the amqp module once things seem to work

(def ^:private no-durable false)
(def ^:private no-auto-delete-exchange false)
(def ^:private auto-delete-queue true)
(def ^:private no-exclusive-queue false)
(def ^:private auto-ack true)
(def ^:private topic-exchange "topic")

(defn- make-consumer-channel!
  [conn exchange queue route]
  (doto (.createChannel conn)
    (.exchangeDeclare exchange topic-exchange no-durable no-auto-delete-exchange nil)
    (.queueDeclare queue no-durable no-exclusive-queue auto-delete-queue nil)
    (.queueBind queue exchange route)))

(defn- make-consumer!
  [conn exchange queue route]
  (let [channel (make-consumer-channel! conn exchange queue route)
        consumer (QueueingConsumer. channel)]
    (doto channel
      (.basicConsume queue auto-ack consumer))
    consumer))

(defn- unsubscribe-loop!
  [bardo pid channel]
  (let [c (filter< #(and (= (first %) :die)
                         (= (second %) pid)) bardo)]
    (go
      (when-let [msg (<! c)]
        (when (.isOpen channel)
          (.close channel)))
      (log/info "unsubscribed" pid))))

(defn- consumer-thread
  "Delegates incoming messages from consumer to delegate. On exception,
   dies, but sends exit message to bardo for possible re-incarnation."
  [bardo pid exchange queue route consumer delegate]
  (put! bardo [:die pid]) ;; just in case
  (unsubscribe-loop! bardo pid (.getChannel consumer))
  (thread
    (try
      (loop []
        (let [msg (.getBody (.nextDelivery consumer))]
          (try (delegate msg)
               (catch Throwable t
                 (log/error "Uncaught delegate exception:" t))))
        (recur))
      (catch Throwable t
        (put! bardo [:exit [pid t exchange queue route delegate]])))))

(defn- reincarnate?
  [exception]
  (not (and (instance? ShutdownSignalException exception)
            (.isInitiatedByApplication exception))))

(defn- reincarnation-loop!
  [conn bardo]
  (go-loop []
    (when-let [[_ pid exception exchange queue route delegate] (<! bardo)]
      (when (reincarnate? exception)
        (when (instance? AlreadyClosedException exception)
          (amqp/restart! conn))
        (let [c (make-consumer! conn exchange queue route)]
          (consumer-thread bardo pid exchange queue route c delegate)))
      (recur))))

;;-----------------------------------------------------------------------------

(defn make-consumers
  [conn]
  (atom {:conn conn :bardo (chan)}))

(defn subscribe!
  [this pid exchange route queue delegate]
  (let [{:keys [conn bardo reaper]} @this
        consumer (make-consumer! conn pid exchange queue route)]
    (consumer-thread bardo conn pid exchange queue route consumer delegate)
    [:subscribed pid]))

(defn unsubscribe!
  [this pid]
  (let [{:keys [conn bardo]} @this]
    (put! bardo [:die pid])
    [:unsubscribed pid]))

(defn start!
  [this]
  (let [{:keys [conn bardo]} @this]
    (reincarnation-loop! conn bardo))
  :started)

(defn stop!
  [this]
  (close! (:bardo @this))
  (swap! this assoc :bardo (chan))
  :stopped)
