(ns testuccx.core-test
  (:require [clojure.test :refer :all]
            [testuccx.core :refer :all]
            [fipp.edn :refer [pprint] :rename {pprint fipp}]
            [clojure.spec.alpha :as spec]
            [expound.alpha :as expound]
            [byte-streams :as bs]
            [aleph.http :as http]
            [manifold.stream :as strm]
            [manifold.deferred :as d]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]
            [lanterna.screen :as scr]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal]]
            ))

(def testconn {:uccxip "9.1.1.62" :uccxname "atea-dev-uccx11" :wallpwd "ateasystems0916" :query "select * from rtcsqssummary"})
(defn update-db []
  (reset! db (make-queues-map (get-wbquery testconn ) )))

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj
     (<!! ch)
     :cljs
     (async done
            (take! ch (fn [_] (done))))))


(defn test-within
  "Asserts that ch does not close or produce a value within ms. Returns a
  channel from which the value can be taken."
  [ms ch]
  (go (let [t (timeout ms)
            [v ch] (alts! [ch t])]
        (is (not= ch t)
            (str "Test should have finished within " ms "ms."))
        v)))


(deftest test1
  (let [ch (chan)]
    (go (>! ch "Hello"))
    (test-async
     (go (is (= "Hello" (<! ch)))))))

(deftest test-getrtstats
  (testing "test of promesa."
    (let [ch (chan)]
      (go (>! ch  (get-rtstats {:priip "9.1.1.62"}))
      (test-within 1000
       (go (is (= "jhole" (<! ch)))))))))


(deftest test-checkmaster
  (testing "test checkmaster"
    (let [ch (chan)]
      (go (>! ch (check-master {:priip "9.1.1.62"}))
      (test-within 1000
       (go (is (= "isMaster" (<! ch))))
       )))))
