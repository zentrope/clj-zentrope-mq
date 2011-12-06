(defproject org.clojars.zentrope/zentrope-mq "0.1.1"
  :description "Convenience lib for fault-tolerant, non-blocking,
                transient, light-weight messaging via Rabbit/MQ."
  :run-aliases {:main zentrope-mq.main }
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [com.rabbitmq/amqp-client "2.7.0"]]
  :dev-dependencies [[org.slf4j/slf4j-api "1.6.4"]
                     [ch.qos.logback/logback-classic "1.0.0"]]
  :clean-non-project-classes true
  :jar-exclusions [#"main.clj" #".DS_Store"]
  :extra-files-to-clean ["pom.xml" "lib"])
