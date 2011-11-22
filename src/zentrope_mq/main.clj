(ns zentrope-mq.main
  (:require [zentrope-mq.core :as mq])
  (:require [clojure.tools.logging :as log]))

(let [lock (promise)]
  (defn- acquire-lock [] (deref lock))
  (defn- release-lock [] (deliver lock 'done)))

(defn- mk-thread
  [thread-name runnable]
  (doto (Thread. runnable)
    (.setName thread-name)))

(defn- shutdown-hook
  [thread-name runnable]
  (.addShutdownHook (Runtime/getRuntime)
                    (mk-thread thread-name runnable)))

(def test-msg (.getBytes "{\"app\" : \"mq-lib\",\"hostaddr\" : \"127.0.0.1\"}"))

(defn- do-pub
  []
  (loop []
    (try
      (do
        (log/info "sending test.mq status")
        (mq/publish :test-mq "default" "system.status" test-msg))
      (catch Throwable t
        (log/error t))
      (finally (Thread/sleep 5000)))
    (recur)))

(defn test-publisher
  []
  (doto (Thread. (fn [] (do-pub)))
    (.setName "test.pub.mq.lib")
    (.start)))

(defn test-delegate
  [msg]
  (log/info "delegate" (String. (.getBody msg))))

(defn- start
  []
  (log/info "Starting mq.main.")
  (mq/start)
  (mq/subscribe :system-status "default" "system.status"
                "test.queue" test-delegate)
  (test-publisher))

(defn- stop
  []
  (log/info "Stopping mq.main.")
  (let [result (mq/unsubscribe :system-status)]
    (log/info "unsubscribe" result))
  (mq/stop))

(defn -main
  [& args]
  (log/info "Hello MQ Test App")
  (shutdown-hook "mq.main.shutdown" (fn []
                                      (stop)
                                      (release-lock)))
  (start)
  (acquire-lock)
  (log/info "Done."))
