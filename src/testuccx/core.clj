(ns testuccx.core
  (:require [cli-matic.core :refer [run-cmd]]
            [clojure.spec.alpha :as spec]
            [expound.alpha :as expound]
            [clojure.edn :as edn]
            [clojure.test.check.generators :as gen]
            [byte-streams :as bs]
            [aleph.http :as http]
            [manifold.stream :as strm]
            [manifold.deferred :as d]
            [io.aviso.ansi :refer [bold-red-font bold-yellow-font bold-red yellow-font blue-font reset-font]]
            [clojure.java.jdbc :as j]
            [clojure.string :as str]
            [fipp.edn :refer [pprint] :rename {pprint fipp}]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]
            [hikari-cp.core :as h]
            [dire.core :refer [with-handler! with-finally!]]
            [lanterna.screen :as scr]
            [clojure.pprint :refer [cl-format]]
            [tick.alpha.api :as t]
            [tick.deprecated.schedule :as tick :refer [schedule]]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report]]
            )
  (:import [java.net ConnectException ServerSocket SocketTimeoutException SocketException URI]
           [com.informix.asf IfxASFRemoteException Connection]
           [java.sql SQLException]
           [java.time DayOfWeek])
  (:gen-class))

(spec/def ::ip-address
  (letfn [(pred [s]
            (let [parts (str/split s #"\.")]
              (and (= (count parts) 4)
                   (every? (fn [part]
                             (try
                               (let [n (edn/read-string part)]
                                 (and (integer? n)
                                      (>= 256 n 0)))
                               (catch Exception _ false)))
                           parts))))
          (gen []
            (gen/fmap (partial str/join ".") (gen/vector (gen/choose 0 255) 4)))]
    (spec/spec pred :gen gen)))

(spec/def ::ip-port
  (spec/int-in 1 65536))

(spec/def ::privileged-ip-port
  (spec/int-in 1 1024))

(spec/def ::unprivileged-ip-port
  (spec/int-in 1025 65536))

(def is-master "Master>true")
(def db (atom {}))

(defn uccx-dbspec
  "Create a UCCX poll system"
  [uccxip uccxname wallpwd]
  (let [ifx-normalised-name (str/replace uccxname #"-" "_")]
    (h/make-datasource {:jdbc-url (str "jdbc:informix-sqli://"
                                       uccxip ":1504/db_cra:informixserver="
                                       ifx-normalised-name "_uccx")
                        :username "uccxwallboard"
                        :password wallpwd
                        :maximum-pool-size 1
                        :read-only true}))
  )

(with-handler! #'uccx-dbspec
  "Catches errors on misconstrued datasource"
  [ConnectException
   SocketTimeoutException
   ServerSocket
    Connection
    IfxASFRemoteException
    Exception
  ]
  (fn [e & args] (let [err             (Throwable->map e)
                       message          (:message (first (:via err)))
                       uccx-name-error (re-find #"DBSERVERNAME" message)
                       passwd-error    (re-find #"password" message) ]
                   (if uccx-name-error
                     (fatal  bold-red-font "¡ Incorrect UCCX Server Name !: " bold-yellow-font message reset-font)
                     (fatal  bold-red-font "¡ UCCX password is WRONG !: " bold-yellow-font message reset-font)
                     ))))

(defn run-statement! [ds s]
  (j/with-db-connection [conn {:datasource ds}]
    (let [result (j/execute! conn s)]
      result)))

(defn run-query! [ds q]
  (j/with-db-connection [conn {:datasource ds}]
    (let [rows (j/query conn q)]
      rows)))

(defn update-vals [m f & args]
  (into {} (for [[k v] m] [k (apply f v args)])))

(defn update-vals-reduce [m f & args]
        (reduce (fn [acc [k v]] (assoc acc k (apply f v args))) {} m))

(defn make-queues-map [ query ]
  (reduce (fn [emptymap {:keys [csqname] :as row}]
            (update-in emptymap
                       [csqname]
                       (fnil conj {})
                       (dissoc row :csqname)))
          {} query) )

(defn ip->url
  [ip]
  (str "http://" ip "/uccx/isDBMaster" ))

(defn check-master-uccx
  "Checks if the IP given is the UCCX DB master.
   returns boolean"
  [ master-ip ]
  (let [url (ip->url master-ip) ]
    (-> (d/chain (http/get url {:connection-timeout 1000})
              :body
              bs/to-string
              )
               (d/catch #(report ConnectException bold-red-font "Whoops wrong IP? - " reset-font (.getMessage %)))
    )
  ))

(with-handler! #'check-master-uccx
  "check for http errors"
  [ConnectException
   Exception]
  (fn [e & args] (let [err    (Throwable->map e)]
                   (report "Wazoo -" (:cause e)))

  ))

(with-finally! #'check-master-uccx
  "An optional docstring about the finally function."
  (fn [e & args]
                   (fatal bold-red-font "Error - " (:message e) reset-font)) )

;;; For a task, specify an exception that can be raised and a function to deal with it.
(with-handler! #'run-query!
  "Here's an optional docstring about the handler."
  [SQLException
   java.io.IOException]
  ;;; 'e' is the exception object, 'args' are the original arguments to the task.
  (fn [e & args] (let [err             (Throwable->map e)
                       message          (:message (first (:via err)))]
                   (fatal bold-red-font "UCCX SQL syntax is incorrect - errmsg: " message reset-font)

    )))


(defn check-master
  "polls Uccx IP and determines master"
  [{:keys [uccxip]}]
  (let [is-master? (re-find #"Master>true" @(check-master-uccx uccxip))]
    {:is-master? (string? is-master?)} )
  )

;;; For a task, specify an exception that can be raised and a function to deal with it.
(with-handler! #'check-master
  "Here's an optional docstring about the handler."
  [SocketException
   java.io.IOException]
  ;;; 'e' is the exception object, 'args' are the original arguments to the task.
  (fn [e & args] (let [err             (Throwable->map e)
                       message          (:message (first (:via err)))]
                   (fatal bold-red-font "Host does NOT exist - errmsg: " message reset-font)

                   )))


(defn get-rtstats
  "Collect real time stats from UCCX"
  [{:keys [uccxip uccxname wallpwd]}]
  (let [is-master? (:is-master? (check-master {:uccxip uccxip}))
        ds    (uccx-dbspec uccxip uccxname wallpwd)
        query "SELECT * from RtICDStatistics"]
    (if is-master?
      (do (fipp (run-query! ds query))
         ;; (h/close-datasource ds)
          )
      (println bold-yellow-font uccxip " is not master! Can't run query" reset-font)

      )
    )
  )

(defn get-wbquery
  "Collect real time stats from UCCX"
  [{:keys [uccxip uccxname wallpwd query]}]
  (let [is-master? (:is-master? (check-master-uccx uccxip))
        ds    (uccx-dbspec uccxip uccxname wallpwd)
        query (str query)]
    (if is-master?
      (run-query! ds query)
      (info bold-yellow-font uccxip " is not master! Can't run query" reset-font)
      )
    ))


(defn print-db []
  (for [[k v ] @db]
    (println (str yellow-font k  " --> "
                  blue-font (:callshandled v)
                  ", agents-avail: " (:availableagents v)
                  ", updated:" (:enddatetime v)
                  reset-font "."))
    ))

(defn leftpad
  "If S is shorter than LEN, pad it with CH on the left."
  ([s len] (leftpad s len " "))
  ([s len ch]
   (cl-format nil (str "~" len ",'" ch "d") (str s))))

(def screen-cols {:csqname 1
                  :callshandled 10
                  :callswaiting 18
                  :callsabandoned 26
                  :avgtalkduration 34
                  :avgwaitduration 42
                  :totalcalls 50
                  :availableagents 58
                  :talkingagents 68
                  :enddatetime 74})

(def screen (scr/get-screen :swing))

(defn print-header [resource-kw colname fg-colour]
  (scr/put-string screen (resource-kw screen-cols) 0 (leftpad colname 6) {:fg fg-colour} ))

(defn print-resource [resource-kw line-num row]
  (scr/put-string screen (resource-kw screen-cols)
                  line-num (leftpad (resource-kw row) 5) {:fg :white}))

(defn setup-screen []
  (scr/clear screen)
  (scr/start screen)
  (scr/put-string screen 1 0 "Queue" {:fg :black :bg :yellow})
  (print-header :callshandled "Hndled" :white)
  (print-header :callswaiting "Waiting" :yellow)
  (print-header :callsabandoned "Abned" :red)
  (print-header :avgtalkduration "AvgTalk" :white)
  (print-header :avgwaitduration "AvgWait" :yellow)
  (print-header :totalcalls "Total" :white)
  (print-header :availableagents "AvlAgts" :green)
  (print-header :talkingagents "TalkAgts" :yellow)
  (scr/redraw screen)
  )

(defn print-resources [ db ]
  )

(comment
  (def testdata [{:csqname "Dev"   :availableagents 7  :callshandled 12 :callsabandoned 7}
                 {:csqname "Sales" :availableagents 15 :callshandled 50 :callsabandoned 42}])
  (defn reduce-map [data]
    (reduce (fn [emptymap {:keys [csqname] :as row}]
              (update-in emptymap
                         [csqname]
                         (fnil conj {})
                         (dissoc row :csqname)))
            {} data ))

  (defn screen-update-rows [db]
  (let rows  (range (count (deref db)))
        )
  (for [[q data] (deref db)]
    (doseq ()))
  )
;; work on non-nested map
  (defn map-keys-map
    [m ks f]
     (merge m
       (into {}
         (for [k ks] [k (f (k m))]))))
  (defn map-values-red
    [m keys f & args]
    (reduce #(apply update-in %1 [%2] f args) m keys))

  (map-values m [:a :b] inc)
  )

(def CONFIGURATION
  {:app         {:command     "testuccx"
                 :description "A quick uccx realtime stats tester"
                 :version     "0.1"}

 :global-opts [{  :option  "uccxip"
                  :as      "The Uccx primary IP addr."
                  :type    :string
                  :default "localhost"
                  :spec ::ip-address}
               ]
 :commands    [{:command     "master" :short "m"
                :description "Polls the UCCX's via http and checks which is master"
                :runs        check-master}
               {:command     "stats"  :short "s"
                :description  "Fetchs the UCCX's RealTime ICD Statistics"
                :opts        [{:option "uccxname" :short "n" :as "Primary UCCX name" :type :string :default "atea_dev_uccx11"}
                              {:option "wallpwd" :short "p" :as "Wallboard User password" :type :string :default "ateasystems0916" }]
                :runs        get-rtstats}
               ]
   })



(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  (run-cmd args CONFIGURATION))
