(ns zentrope-mq.impl.producers
  (:require
    [zentrope-mq.impl.conn2 :as conn]
    [clojure.tools.logging :as log]
    [clojure.core.async :refer [go go-loop <! <!! put! chan close! filter<]]))

;;-----------------------------------------------------------------------------

(defn- pub-loop!
  [this pid exchange route]
  (let [{:keys [conn msg-q]} @this
        input-q (filter< #(= (first %) pid) msg-q)
        amq-channel (conn/make-channel! conn exchange)]
    (go-loop []
      (when-let [[_ msg] (<! input-q)]
        (if-let [worked (conn/basic-publish! conn amq-channel exchange route msg)]
          (recur)
          :done))
      (conn/close-channel! conn amq-channel)
      :done)))

(defn- bootstrap!
  [this pid exchange route]
  (let [p {:pid pid :exchange exchange :route route}]
    (swap! this (fn [s] (update-in s [:producers] conj pid)))
    (go
      (<!! (pub-loop! this pid exchange route))
      (swap! this (fn [s] (update-in s [:producers] disj pid))))))

;;-----------------------------------------------------------------------------

(defn publish!
  [this pid exchange route data]
  (let [{:keys [conn msg-q producers]} @this]
    (when-not (conn/open? conn)
      (conn/restart! conn))
    (when-not (contains? producers pid)
      (bootstrap! this pid exchange route))
    (when (conn/open? conn)
      (put! msg-q [pid data]))))

(defn make
  [conn]
  (atom {:conn conn :msg-q nil :producers #{}}))

(defn start!
  [this]
  (swap! this assoc :msg-q (chan))
  :started)

(defn stop!
  [this]
  (close! (:msg-q @this))
  (swap! this assoc :producers nil :msg-q nil)
  :stopped)
