(ns triplema.core
  (:import (java.util Date
                      Collections)
           (java.text SimpleDateFormat)
           (com.lmax.api Callback
                         FixedPointNumber
                         LmaxApi)
           (com.lmax.api.account LoginCallback
                                 LoginRequest)
           (com.lmax.api.heartbeat HeartbeatCallback
                                   HeartbeatRequest)
           (com.lmax.api.order AmendStopsRequest
                               ExecutionEventListener
                               ExecutionSubscriptionRequest
                               LimitOrderSpecification
                               OrderCallback
                               OrderEventListener
                               OrderType)
           (com.lmax.api.orderbook OrderBookEventListener
                                   OrderBookSubscriptionRequest))
  (:gen-class))


(def conf)

;; lmax
;(def instrumentId 100437) ; 24/7 test instrument
(def instrumentId 4001) ; EURUSD
(def session nil)
(def heartbeat-frequency 720000)

(def bricksize 100)
(def shortavglen 25)
(def medavglen 80)
(def longavglen 200)

;; trading data
(def currentorder (atom nil))
(def bricks (atom (vector)))
(def MMAs (atom (vector)))

(def stopstrack (ref {:loss nil :profit nil}))


;; utility functions

(defn log [& args]
  (spit "traderbot.log"
        (str (.format (new SimpleDateFormat "yyyy/MM/dd HH:mm:ss") (new Date)) ; prepend date
             " "
             (apply format args)
             "\n")
        :append true))

(defn lispify [x]
  (cond (= (type x) FixedPointNumber)
        (.longValue x)
        (= (type x) java.util.Collections$UnmodifiableRandomAccessList)
        (map #(list (.longValue (.getPrice %)) (.longValue (.getQuantity %))) (apply list x))
        :else x))

(defn objtosexpr [object]
  (str (apply list (map lispify (apply list (-> (dissoc (bean object) :class) seq flatten))))))

(defn abs [number]
  (if (< number 0)
    (- number)
    number))

(defn avg-serie [n l]
  (/ (apply + (take-last n l)) n))

(defn avg [x]
  (/ (apply + x)
     (count x)))

(defn fitpriceinc [x]
  (long (* (quot x 10) 10)))

(defn long? [q]
  (> (.signum q) 0))



(defn heartbeatCallback []
  (proxy [HeartbeatCallback] []
    (onSuccess [token]
               (log "sent heartbeat token : %s" token))
    (onFailure [failureResponse]
               (log "send heartbeat failed : %s" failureResponse))))

(defn heartbeat-loop []
  (while (not (.isInterrupted (Thread/currentThread)))
    (Thread/sleep heartbeat-frequency)
    (.requestHeartbeat session (HeartbeatRequest. (str (System/currentTimeMillis))) (heartbeatCallback))))

(defn amend-stop [instrumentid originstructionid loss profit]
  (log (format "amend-stop loss %s profit %s%n" loss profit))
  (let [preploss (if loss (FixedPointNumber/valueOf (fitpriceinc (long loss))) nil)
	prepprofit (if profit (FixedPointNumber/valueOf (fitpriceinc (long profit))) nil)]
    (log (format "amend-stop : instrumentid : %s, originstructionid : %s, loss : %s, profit : %s, preploss : %s, prepprofit : %s%n"
                 instrumentid originstructionid loss profit preploss prepprofit))
    (dosync (ref-set stopstrack {:loss loss :profit profit}))
    (.amendStops session (AmendStopsRequest. instrumentid originstructionid preploss prepprofit)
		 (proxy [OrderCallback] []
		   (onSuccess [amendRequestInstructionId]
			      (log (format "amend stop request %s for order %s : success%n" amendRequestInstructionId originstructionid)))
		   (onFailure [failureReason]
			      (log (format "amend stop request failed. Reason : %s%n" failureReason)))))))

; ajuster le stop si nécessaire (pas d'ajustement à la baisse)
(defn adjust-stops [order currentprice stoploss takeprofit]
  (try 
    (let [stopReferencePrice (.longValue (.getStopReferencePrice order))
          quantitySign (.signum (.getFilledQuantity order))
          oldstoploss (if (:loss @stopstrack)
                        (:loss @stopstrack)
                        (.longValue (.getStopLossOffset order)))
          newstoploss (if (long? (.getFilledQuantity order))
                        (- stoploss (- currentprice stopReferencePrice))
                        (+ stoploss (- currentprice stopReferencePrice)))  ; calcul ajustement stop loss glissant
          oldtakeprofit (.longValue (.getStopProfitOffset order))
          newtakeprofit (if (long? (.getFilledQuantity order))
                          (+ takeprofit (- currentprice stopReferencePrice))
                          (- takeprofit (- currentprice stopReferencePrice)))
          closerstoploss (if (< newstoploss oldstoploss) newstoploss oldstoploss)]
      (amend-stop instrumentId (.getOriginalInstructionId order) closerstoploss newtakeprofit))
    (catch Exception e
      (if (= (.getOrderType order) OrderType/STOP_PROFIT_ORDER)
        (dosync (ref-set currentorder nil))))))

(defn placedorderCallback []
  (proxy [OrderCallback] []
    (onSuccess [instructionId]
      (log "ordre passé : %s" (str instructionId)))
    (onFailure [failureResponse]
      (log "echec passage d'ordre : %s" failureResponse))))

(defn placeorder [limit quantity stoploss stopprofit]
  (try
    (log "PlaceOrder %s" limit)
    (let [orderspec (fn [l q stop profit]
                      (LimitOrderSpecification. instrumentId
                                                (FixedPointNumber/valueOf (long l))
                                                (FixedPointNumber/valueOf (long (* q 1000000))) ; 1000000L = FixedPointNumber/ONE
                                                com.lmax.api.TimeInForce/IMMEDIATE_OR_CANCEL
                                                (FixedPointNumber/valueOf (long stop))
                                                (if profit (FixedPointNumber/valueOf (long profit)) nil)))]
      (.placeLimitOrder session (orderspec limit quantity stoploss stopprofit) (placedorderCallback)))
    (catch Exception e
      (log "exception %s" e))))


;; core functions

(defn fire-orders []
  (if (not @currentorder)
    (cond (apply > (last @MMAs)) ; up
          (placeorder (+ bricksize (last @bricks)) 5 800 2500) ; buy
          (apply < (last @MMAs)) ; down
          (placeorder (- (last @bricks) bricksize) -5 800 2500)) ; sell
    (adjust-stops @currentorder)))

(defn update-at []
  (let [shortval (avg-serie shortavglen @bricks)
        medval (avg-serie medavglen @bricks)
        longval (avg-serie longavglen @bricks)]
    (swap! MMAs (fn [x] (conj x (list shortval medval longval)))))
  (if (> (count @bricks) medavglen)
    (fire-orders)))

(defn update-bricks [orderbookevent]
  (let [bid (.longValue (.getPrice (.get (.getBidPrices orderbookevent) 0)))
        ask (.longValue (.getPrice (.get (.getAskPrices orderbookevent) 0)))
        averageprice (avg (list bid ask))
        rawdiff (if (last @bricks)
                  (quot (- averageprice (last @bricks)) bricksize)
                  nil)]
    (cond (= (count @bricks) 0) ; first brick
          (reset! bricks (vector (* (quot averageprice bricksize) bricksize)))
          (> (abs rawdiff) 0) ; new brick
          (do (swap! bricks (fn [x] (conj x (+ (last @bricks) (* rawdiff bricksize)))))
              (update-at)))
    
    (log (format "servertimestamp : %s avgprice : %s (bid : %s, ask : %s), curbrick : %s"
                 (.getTimeStamp orderbookevent) (long averageprice)
                 (long bid) (long ask) (last @bricks)))))


;; callbacks

(defn defaultSubscriptionCallback []
  (proxy [Callback] []
    (onSuccess []
      (log "Default success callback"))
    (onFailure [failureResponse]
      (log "Default failure callback %s" failureResponse))))


(defn orderbookeventCallback []
  (proxy [OrderBookEventListener] []
    (notify [orderbookevent]
      (update-bricks orderbookevent))))

(defn ordereventCallback []
  (proxy [OrderEventListener] []
    (notify [order]
      )))

(defn executioneventCallback []
  (proxy [ExecutionEventListener] []
    (notify [execution]
      (let [order (.getOrder execution)
            instructionid (.getInstructionId order)
            ordertype (.getOrderType (.getOrder execution))
            execprice (.getPrice execution)]
        (log (format "Order %s executed, type %s, price %s"
                     instructionid ordertype (str execprice)))

        (if execprice
          (if (or (= ordertype OrderType/STOP_PROFIT_ORDER)
                  (= ordertype OrderType/STOP_LOSS_ORDER))
            ; cloture
            (reset! currentorder nil)
            ; ouverture
            (reset! currentorder order)))))))


(defn loginCallbacks []
  (proxy [LoginCallback] []
    (onLoginSuccess [session]
      (def session session)
      (log "Logged in, account details : %s" (.getAccountDetails session))
      ;; LMAX subscriptions
      (.registerOrderBookEventListener session (orderbookeventCallback))
      (.subscribe session (OrderBookSubscriptionRequest. instrumentId) (defaultSubscriptionCallback))
      (.registerOrderEventListener session (ordereventCallback))
      (.registerExecutionEventListener session (executioneventCallback))
      (.subscribe session (ExecutionSubscriptionRequest.) (defaultSubscriptionCallback))
      (.start (Thread. heartbeat-loop)) ; start heartbeat thread
      (.start session))
    (onLoginFailure [failureResponse]
      (log "Failed to login. Reason : %s" failureResponse))))


(defn login [name password demo]
  (let [url (if demo
              "https://testapi.lmaxtrader.com"
              "https://trade.lmaxtrader.com")
        prodtype (if demo
                   com.lmax.api.account.LoginRequest$ProductType/CFD_DEMO
                   com.lmax.api.account.LoginRequest$ProductType/CFD_LIVE)]
    (log "logging in %s..." url)
    (.login (LmaxApi. url)
            (LoginRequest. name password prodtype)
            (loginCallbacks))))


(defn -main [& args]
  (let [conf (read-string (slurp "config.clj"))]
    (while true (do (login (:login conf) (:password conf) (:demo conf))
                    (log "Disconnected, try to login again...")))))
