(ns puppetlabs.cthun.connection-states
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.cthun.activemq :as activemq]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.cthun.validation :as validation]
            [puppetlabs.cthun.metrics :as metrics]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [metrics.counters :refer [inc!]]
            [metrics.meters :refer [mark!]]
            [metrics.timers :refer [time!]]
            [ring.adapter.jetty9 :as jetty-adapter]))

(def ConnectionState
  "The state of a websocket in the connection-map"
  {:client s/Str
   :type s/Str
   :status s/Str
   (s/optional-key :uri) message/Uri
   :created-at message/ISO8601})

(def connection-map (atom {})) ;; Map of ws -> ConnectionState

(def uri-map (atom {})) ;; { uri => ws }

(def inventory (atom {}))

(def delivery-executor (atom {})) ;; will be replaced with an ExecutorService

(defn set-delivery-executor
  "Sets the delivery executor"
  [executor]
  (reset! delivery-executor executor))

(defn- make-uri
  "Make a new given a common name and type"
  [common-name type]
  (str "cth://" common-name "/" type))

(defn websocket-for-uri
  "Return the websocket a node identified by a uri is connected to, false if not connected"
  [uri]
  (get @uri-map uri))

(s/defn ^:always-validate
  new-socket :- ConnectionState
  "Return a new, unconfigured connection map"
  [client]
  {:client client
   :type "undefined"
   :status "connected"
   :created-at (ks/timestamp)})

(defn- logged-in?
  "Determine if a websocket combination is logged in"
  [host ws]
  (= (get-in @connection-map [ws :status]) "ready"))

(defn- handle-delivery-failure
  [message reason]
  "If the message is not expired schedule for a future redelivery by adding to the redeliver queue"
  (log/info "Failed to deliver message" message reason)
  (let [expires (time-coerce/to-date-time (:expires message))
        now     (time/now)]
    (if (time/after? expires now)
      (let [difference  (time/in-seconds (time/interval now expires))
            retry-delay (if (<= (/ difference 2) 1) 1 (float (/ difference 2)))
            message     (message/add-hop message "redelivery")]
        (log/info "Moving message to the redeliver queue for redelivery in" retry-delay "seconds")
        (activemq/queue-message "redeliver" message (mq/delay-property retry-delay :seconds)))
      (log/warn "Message " message " has expired. Dropping message"))))

(defn deliver-message
  [message]
  "Delivers a message to the websocket indicated by the :_target field"
  (if-let [websocket (websocket-for-uri (:_target message))]
    (try
      ; Lock on the websocket object allowing us to do one write at a time
      ; down each of the websockets
      (locking websocket
                 (inc! metrics/total-messages-out)
                 (mark! metrics/rate-messages-out)
                 (let [message (message/add-hop message "deliver")]
                   (jetty-adapter/send! websocket (message/encode message))))
      (catch Exception e
        (handle-delivery-failure message e)))
    (handle-delivery-failure message "not connected")))

(defn expand-targets
  "Return the targets a message should be delivered to"
  [message]
  ((:find-clients @inventory) (:targets message)))

(defn make-delivery-fn
  "Returns a Runnable that delivers a message to a :_destination"
  [message]
  (fn []
    (log/info "delivering message from executor to websocket" message)
    (let [message (message/add-hop message "deliver-message")]
      (deliver-message message))))

(declare process-client-message) ;; TODO(richardc) restructure to avoid forward decl, maybe rename

(defn maybe-send-destination-report
  "Send a destination report about the given message, if requested"
  [message targets]
  (if (:destination_report message)
    (let [report {:id (:id message)
                  :targets targets}
          reply (-> (message/make-message)
                    (assoc :id (ks/uuid)
                           :expires ""
                           :targets [(:sender message)]
                           :message_type "http://puppetlabs.com/destination_report"
                           :sender "cth://server")
                    (message/set-json-data report))]
      (s/validate validation/DestinationReport report)
      (process-client-message nil nil reply))))

(defn deliver-from-accept-queue
  "Message consumer.  Accepts a message from the accept queue, expands
  destinations, and attempts delivery."
  [message]
  (let [targets (expand-targets message)
        messages (map #(assoc message :_target %) targets)]
    (maybe-send-destination-report message targets)
    (doall (map #(.execute @delivery-executor (make-delivery-fn %)) messages))))

(defn deliver-from-redelivery-queue
  "Message consumer.  Accepts a message from the redeliver queue, and
  attempts delivery."
  [message]
  (.execute @delivery-executor (make-delivery-fn message)))

(defn subscribe-to-topics
  [accept-threads redeliver-threads]
  (activemq/subscribe-to-topic "accept" deliver-from-accept-queue accept-threads)
  (activemq/subscribe-to-topic "redeliver" deliver-from-redelivery-queue redeliver-threads))

(defn use-this-inventory
  "Specify which inventory to use"
  [new-inventory]
  (reset! inventory new-inventory))

(defn- process-client-message
  "Process a message directed at a connected client(s)"
  [host ws message]
  (message (message/add-hop message "accept-to-queue"))
  (time! metrics/time-in-message-queueing
         (activemq/queue-message "accept" message)))

(defn- login-message?
  "Return true if message is a login type message"
  [message]
  (and (= (first (:targets message)) "cth:///server")
       (= (:message_type message) "http://puppetlabs.com/loginschema")))

(defn add-connection
  "Add a connection to the connection state map"
  [host ws]
  (swap! connection-map assoc ws (new-socket host)))

(defn remove-connection
  "Remove a connection from the connection state map"
  [host ws]
  (if-let [uri (get-in @connection-map [ws :uri])]
    (swap! uri-map dissoc uri))
  (swap! connection-map dissoc ws))

(defn- process-login-message
  "Process a login message from a client"
  [host ws message-body]
  (let [data (message/get-json-data message-body)]
    (log/info "Processing login message" data)
    (when (validation/validate-login-data data)
      (log/info "Valid login message received")
      (if (logged-in? host ws)
        (let [current (get-in @connection-map [ws])]
          (log/error "Received login attempt for '" host "/" (:type data) "' on socket '"
                     ws "'.  Socket was already logged in as " host "/" (:type current)
                     " connected since " (:created-at current)
                     ".  Closing new connection.")
          (jetty-adapter/close! ws))
        (let [type (:type data)
              uri (make-uri host type)]
          (if-let [old-ws (websocket-for-uri uri)]
            (do
              (log/error "node with uri " uri " already logged in on " old-ws " Closing new connection")
              (jetty-adapter/close! ws))
            (do
              (swap! connection-map update-in [ws] merge {:type type
                                                          :status "ready"
                                                          :uri uri})
              (swap! uri-map assoc uri ws)
              ((:record-client @inventory) uri)
              (log/info "Successfully logged in " host "/" type " on websocket: " ws))))))))

(defn- process-inventory-message
  "Process a request for inventory data"
  [host ws message]
  (log/info "Processing inventory message")
  (let [data (message/get-json-data message)]
    (when (validation/validate-inventory-data data)
      (log/info "Valid inventory message received")
      (let [uris ((:find-clients @inventory) (:query data))
            response-data {:uris uris}
            response (-> (message/make-message)
                         (assoc :id (ks/uuid)
                                :expires ""
                                :endpoints [(get-in @connection-map [ws :uri])]
                                :message_type "http://puppetlabs.com/inventoryresponseschema"
                                :sender "cth:///server")
                         (message/set-json-data response-data))]
        (process-client-message host ws response)))))

(defn- process-server-message
  "Process a message directed at the middleware"
  [host ws message]
  (log/info "Procesesing server message")
  ; We've only got two message types at the moment - login and inventory
  ; More will be added as we add server functionality
  ; To define a new message type add a schema to
  ; puppetlabs.cthun.validation, check for it here and process it.
  (let [message-type (:message_type message)]
    (case message-type
      "http://puppetlabs.com/loginschema" (process-login-message host ws message)
      "http://puppetlabs.com/inventoryschema" (process-inventory-message host ws message)
      (log/warn "Invalid server message type received: " message-type))))

(defn message-expired?
  "Check whether a message has expired or not"
  [message]
  (let [expires (:expires message)]
    (time/after? (time/now) (time-coerce/to-date-time expires))))

(defn process-message
  "Process an incoming message from a websocket"
  [host ws message-body]
  (log/info "processing incoming message")
  ; check if message has expired
  (if (message-expired? message-body)
    (log/warn "Expired message with id '" (:id message-body) "' received from '" (:sender message-body) "'. Dropping.")
  ; Check if socket has been logged into
    (if (logged-in? host ws)
  ; check if this is a message directed at the middleware
      (if (= (get (:targets message-body) 0) "cth:///server")
        (process-server-message host ws message-body)
        (process-client-message host ws message-body))
      (if (login-message? message-body)
        (process-server-message host ws message-body)
        (log/warn "Connection cannot accept messages until login message has been "
                  "processed. Dropping message.")))))
