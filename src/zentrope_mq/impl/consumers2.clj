(ns zentrope-mq.impl.consumers
  ;;
  ;; Rewrite
  ;;
  (:require
    [clojure.tools.logging :as log]
    [clojure.core.async :refer [chan thread <! close!]]
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

;;-----------------------------------------------------------------------------
;;
;; The old one worked by starting up a thread which blocked waiting
;; for an incoming message and then delegated to a callback.
;;
;; If something went wrong, the thread would die after removing an ID
;; from the "live" list and adding it to the "dead" list. If the
;; thread was interrupted due to a legit reason, it did not add itself
;; to the dead list.
;;
;; I supervisor would restart things in the dead list.
;;
;;-----------------------------------------------------------------------------
;;
;; So, rewrite? How about the "dead" list is just a queue. When a
;; consumer process dies, it sends a message to the
;; reincarnation-queue and then lets go.
;;
;; The consumer queue itself can be a thread, I guess, unless we want
;; to use a channel rather than a callback for message delegation.
;;
;;-----------------------------------------------------------------------------
;;
;; Unsubscribe: Set up a go loop per channel. When something shows up in
;; the channel, kill the consumer. This will cause the consumer-thread to
;; die. How do we keep the consumer from reincarnation? Hm. Might be what the
;; .isInitiatedByApplication means for the ShutdownSignal thing.
;;
;; Also, might be worth porting this to http://clojurerabbitmq.info
;;
;;-----------------------------------------------------------------------------

(defn- consumer-thread
  "Delegates incoming messages from consumer to delegate. On exception,
   dies, but sends exit message to bardo for possible re-incarnation."
  [bardo pid exchange queue route consumer delegate]
  (thread
    (try
      (loop []
        (let [msg (.getBody (.nextDelivery consumer))]
          (try (delegate msg)
               (catch Throwable t
                 (log/error "Uncaught delegate exception:" t))))
        (recur))
      (catch Throwable t
        (put! bardo [:exit pid t exchange queue route delegate])))))

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
          (consumer-thread bardo pid exchange queue route c delegate))
        (recur)))))

;;-----------------------------------------------------------------------------

(defn make-consumers
  [conn]
  (atom {:conn conn :bardo (chan)}))

(defn subscribe!
  [this pid exchange route queue delegate]
  (let [{:keys [conn bardo]} @this
        c (make-consumer! conn pid exchange queue route)]
    (consumer-thread bardo conn pid exchange queue route c delegate)))

(defn unsubscribe!
  [this])

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
