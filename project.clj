(defproject zentrope-mq "1.0.0-SNAPSHOT"
  :description "Covenience lib for fault-tolerant, non-blocking, casual Rabbit/MQ."
  :run-aliases {:main zentrope-mq.main }
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [com.rabbitmq/amqp-client "2.7.0"]]
  :dev-dependencies [[org.slf4j/slf4j-api "1.6.4"]
                     [ch.qos.logback/logback-classic "1.0.0"]]
  :clean-non-project-classes true
  :extra-files-to-clean ["pom.xml" "lib"])
