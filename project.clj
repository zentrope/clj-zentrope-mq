(defproject org.clojars.zentrope/zentrope-mq "0.1.1"
  :description "Light-weight, fault-tolerant messaging via Rabbit/MQ."
  :url "https://github.com/zentrope/clj-zentrope-mq"
  :license {:name "Eclipse Public License" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [com.rabbitmq/amqp-client "3.2.2"]]

  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.slf4j/slf4j-api "1.7.5"]
                                  [ch.qos.logback/logback-classic "1.0.13"]]}}
  :jar-exclusions [#"main.clj" #".DS_Store"]
  :main ^:skip-aot zentrope-mq.main
  :jvm-opts ["-Dapple.awt.UIElement=true"]
  :min-lein-version "2.3.4"
  :target-path "target/%s")
