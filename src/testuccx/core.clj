(ns testuccx.core
  (:require [cli-matic.core :refer [run-cmd]]
            [httpurr.status :as s]
            [httpurr.client.aleph :as http]
            [promesa.core :as p]
            [byte-streams :as bs]
            [manifold.stream :as strm]
            [manifold.deferred :as d]
            [io.aviso.ansi :refer [bold-red-font bold-yellow-font reset-font]]
            [clojure.java.jdbc :as j]
            [clojure.string :as str]
            [fipp.edn :refer [pprint] :rename {pprint fipp}]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]
            [hikari-cp.core :as h]
            [dire.core :refer [with-handler! with-finally!]]
            [mount.core :refer [defstate]]
            )
  (:import (java.net ConnectException ServerSocket SocketTimeoutException SocketException URI)
           (com.informix.asf IfxASFRemoteException Connection)
           (java.sql SQLException))
  (:gen-class))

(def is-master "Master>true")

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
                     (println  bold-red-font "UCCX Server Name is wrong - errmsg: " message reset-font)
                     (println  bold-red-font "UCCX password is incorrect - errmsg: " message reset-font)
                     ))))

(defn run-statement! [ds s]
  (j/with-db-connection [conn {:datasource ds}]
    (let [result (j/execute! conn s)]
      result)))

(defn run-query! [ds q]
  (j/with-db-connection [conn {:datasource ds}]
    (let [rows (j/query conn q)]
      rows)))

(defn check-master-uccx
  "Checks if the IP given is the UCCX DB master.
   returns boolean"
  [ hostip ]
  (let [url (str "http://" hostip  "/uccx/isDBMaster") ]
    (d/chain (http/get url)
           :body
           bs/to-string
           )
    )
  )



;;; For a task, specify an exception that can be raised and a function to deal with it.
(with-handler! #'run-query!
  "Here's an optional docstring about the handler."
  [SQLException
   java.io.IOException]
  ;;; 'e' is the exception object, 'args' are the original arguments to the task.
  (fn [e & args] (let [err             (Throwable->map e)
                       message          (:message (first (:via err)))]
                   (println bold-red-font "UCCX SQL syntax is incorrect - errmsg: " message reset-font)

    )))

(with-finally! #'check-master-uccx
  "An optional docstring about the finally function."
  (fn [& args] (println bold-red-font "Executing a finally clause." args reset-font)))

(defn process-response

  [response]
  (condp = (:status response)
    s/ok           (p/resolved (bs/to-string (:body response)))
    s/not-found    (p/rejected :not-found (bs/to-string (:head response)))
    s/unauthorized (p/rejected :unauthorized)))

(defn ip->url
  [ip]
  (str "http://" ip "/uccx/isDBMaster" ))

(defn entity [ip]
  (p/then (http/get (ip->url ip) {:timeout 1})
          process-response))



(defn check-master
  "polls Uccx IP and determines master"
  [{:keys [uccxip]}]
  (let [is-master? (re-find #"Master>true" @(entity uccxip))]
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
                   (println bold-red-font "Host does NOT exist - errmsg: " message reset-font)

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



(def CONFIGURATION
  {:app         {:command     "testuccx"
                 :description "A quick uccx tester"
                 :version     "0.1"}

 :global-opts [{  :option  "uccxip"
                  :as      "The Uccx primary IP addr."
                  :type    :string
                  :default "localhost"}
               ]
 :commands    [{:command     "master" :short "m"
                :description "Polls the UCCX's via http and checks which is master"
                :runs        check-master}
               {:command     "stats"  :short "s"
                :description  "Fetchs the UCCX's RealTime ICD Statistics"
                :opts        [{:option "uccxname" :short "n" :as "Primary UCCX name" :type :string :default "atea_dev_uccx11"}
                              {:option "wallpwd" :short "p" :as "Wallboard User password" :type :string :default "ateasystems0916"}]
                :runs        get-rtstats}
               ]
   })



(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  (run-cmd args CONFIGURATION))
