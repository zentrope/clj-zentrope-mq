<!-- -*- mode: markdown; global-hl-line-mode: nil; -*- -->

# Zentrope (Rabbit) MQ

Zentrope MQ is a covenience library for fault-tolerant, non-blocking,
transient, light-weight messaging via Rabbit/MQ, written in and for
Clojure.

Declaring

    [org.clojars.zentrope/zentrope.mq "1.0.0-SNAPSHOT"]

in leiningen gets you what you need. (NOTE: This app uses
`clojure.tools.logging` for status information, so you might want to
make sure you're using something compatible with that.)

## Motivation

This library caches "producers" and "consumers" such that they're
hidden behind a clean façade which uses a single connection-per-jvm
for all pub/sub. All producers and consumers can be restored under
stormy network conditions without operational intervention on the part
of the application itself.

My main use case is for apps wanting to send light-weight status or
performance metrics to a generic topic for pickup by consumers
interested in subject matter.

My main concern is fault-tolerant connections, _not_ durable,
persistent, guaranteed messaging.

In other words:

```clojure
(ns my.app
  (:require [zentrope-mq.core :as mq]))

(def exchange "default")
(def route "system.status")

(defn log-msg
  [msg]
  (log/info "message from" route (String. (.getBody msg))))

(def send-status (partial mq/publish :sys.stat exchange route))

(mq/start)

(mq/subcribe :sys.stat exchange route "stat.q" log-msg)

(mq/publish :sys.stat exchange route (.getBytes "ok"))

(send-status (.getBytes "oops"))

(app-runs-for-a-long-time)

(mq/stop)
```

That sort of thing. All the connection management and caching is
handled by the library so you can pretend that sending and receiving
messages is easy to do in an unreliable network environment.

## Use cases

This library works for the following scenario:

  * You want to send light-weight status or logging messages to a
    topic to be picked up by some other process for display or
    analysis.

  * You don't care if the messaging infrastructure drops messages if the
    network or MQ broker is down.

  * You don't care if, under bad-weather circumstances, the messages
    don't always get delivered.

  * Once you start your app, you don't want to have to restart it if
    the MQ broker goes down or never came up. When the broker returns,
    you want your messages to start flowing again.

Not many folks need messaging for this sort of thing but I use it a
lot for scenarios where our operational needs aren't yet nailed
down. The most important aspect of this is the fault-tolerant nature
of the consumers. I want them to come back up when it's possible to do
so, and I want that concern hidden away from any of the "business
logic" code.

## Anti use-cases

Just to re-emphasize, if you have these concerns, you might want to
try something else, or fork and fix if there's something about this
library that is nevertheless appealing.

  * If you're looking for a convenient wrapper around the Rabbit MQ
    Java driver for Clojure, this is NOT for you.

  * If you're looking for a general AMQP library for Clojure, this is
    not for you.

  * If you're worried about dropping messages when RabbitMQ or the
    network goes down, this library is NOT for you. It will not block
    until things resume.

I'd love to write a convenience lib for publishers that block when MQ
is down, then resume when it comes back up, but this isn't that.

## Opinionated Design

I've "hardcoded" my use of Rabbit MQ accordingly:

  * No user/password auth (yet).

  * All exchanges are "topic" based because that's the semantics this
    library generally supports.

  * No messages are durable or will survive broker restarts.

  * No exchanges or queues are durable.

  * If you shut down all clients using this lib and examine Rabbit
    MQ, there should be no detritus left over.

In other words, fire and forget, with the emphasis on _forget_. My
assumption is that gaps in data aren't important, so configuration
toward that end is non-existent and security is a bit lax. I think a
different library with a different set of opinions might work better
than mixing them in here.

## API

Just import the `zentrope-mq.core` namespace into your project:

```clojure
(ns your-app.module
  (:require [zentrope-mq.core :as mq]))
```

### Start

To initialize the library, call the following before you subscribe or
publish:

    (mq/start)

A future version will remove this necessity.

### Stop

It's a good idea to call `stop` when your app is going to exit to make
sure that any support threads get the chance to clean up resources.

    (mq/stop)

A future version will remove the need for this.

### Subscribe

Subscribing is the action of registering a callback for a given
exchange, routing key and queue combination. You'll also need to
supply a unique `client-key` to represent the subscriber, which I'll
explain below.

```clojure
(subscribe :client-key exchange route queue-name handler-fn)
```

The `handler-fn` is a function which takes a rabbit-mq message. You
can unpack it as you see fit. For example:

```clojure
(defn handler
  [msg]
  (let [doc (json/read-json (String. (.getBody msg)))]
    (log/info "doc" doc)))
```

It's possible even in this opinionated library to have
multiple subscribers to the same queue. For instance:

```clojure
(subscribe :key-1 ex rte "stat.q" handler-fn)
(subscribe :key-2 ex rte "stat.q" handler-fn)
```

The subscribers are differentiated by the client-key parameter. In
this particular case, the RabbitMQ back-end will round-robin incoming
messages on two separate threads. However, if you need that kind of
power, this is probably the wrong library for you.

You can also receive the same message in multiple places by using
different queue names:

```(clojure
(subscribe :key-1 ex rte "stat.q.1" log-for-analytics)
(subscribe :key-2 ex rte "stat.q.2" save-most-recent-for-gui)
```

That's about all there is to it.

### Unsubscribe

Although, in general, my assumption (and I make a ton of them) is that
once you start up a subscriber, you're going to keep it going for the
life of your application. But maybe you're working at the `repl` or
something like that and you need for the subscriber to stop
listening. Regardless of why, you can shut down a subscriber as
follows:

```clojure
(mq/unsubscribe :client-key)
```

### Publish

Publishing a message means to send data to a specific exchange, tagged
with a specific routing key. Or, as per the opinionated section above,
sending a message to a topic:

```clojure
(publish :pub-key exchange route data)
```

Right now, it's a design-wart that you have to include the exchange
and route and client-key in every request, but that should get
smoothed out. Suffice it to say that the client-key enables
`zentrope-mq` to properly cache a publisher for you so that a new
channel isn't opened with every send.

## Configuration

Right now, `zentrope-mq` is configured via JVM style properties or
Unix style enviroment variables.

**JVM System properties**

You can set up multiple RabbitMQ servers and `zentrope-mq` will
attempt to failover from one to the other if it can.

    -Drabbit.servers=10.254.7.86:5672,10.254.7.103:5672,mq.example.com:5672

Ports are required for now.

**Unix Environment**

You can also use a typical environment variable to configure
`rabbitmq` connection parameters:

    export RABBIT_SERVERS="10.254.7.86:5672,10.254.7.103:5672,mq.example.com:5672"

If you do both, JVM system properties take precedence over the
environment variable.

## TODO

A list of plans:

  * Start propper non-snapshot versioning.
  * Add user/pass properties.
  * Enable non-property configuration.
  * Remove the need to call start/stop.
  * Remove port requirement from server property.
  * Clarify exchange/route-key vs topics.
  * Document the specific RabbitMQ message class passed to the handler
    function.
  * Handler-fn should take a simple structure rather than a Java
    class, like a map of any properties and a `:body` attribute with
    the actual payload.
  * The publisher should take a map of attributes + payload, rather
    than a `byte-array`.
  * Figure out how to include logging without requiring users to
    import implementations.

## License

How about BSD? Yeah, that sounds good.
