(ns testuccx.core
  (:require [cli-matic.core :refer [run-cmd]]
            [clj-http.client :as client]
            [httpurr.status :as s]
            [httpurr.client.aleph :as http]
            [promesa.core :as p]
            [io.aviso.ansi :refer [bold-red-font reset-font]])

  (:gen-class))


(import '(java.util.concurrent TimeoutException TimeUnit))

(defn process-response
  [response]
  (condp = (:status response)
    s/ok           (p/resolved (:body response))
    s/not-found    (p/rejected :not-found)
    s/unauthorized (p/rejected :unauthorized)))

(defn ip->url
  [ip]
  (str "http://" ip "/uccx/isDBMaster" ))

(defn entity [ip]
  (p/then (http/get (ip->url ip) {:timeout 1})
          process-response))

(defn check-master
  "polls Uccxs' and determines master"
  [{:keys [priip secip]}]

  (let [future (client/get  "http://" + priip + "/"
                            {:async true :oncancel #(println (str "Request took longer " bold-red-font "< 1 sec"))}
                         #(println :got %) #(println :err %))]
  (try
    (.get future 1 TimeUnit/SECONDS)
    (catch TimeoutException e
      ;; Cancel the request, it's taken too long
      (.cancel future true))))


  )



(defn get-rtstats
  "Collect realTime STats from UCCX"
  [{:keys [priip priname wallpwd]}]
  (println (str "This function " bold-red-font "ISNT implemented as yet" reset-font "."))
  (entity priip)
  )






(def CONFIGURATION
  {:app         {:command     "uccxtest"
                 :description "A quick uccx tester"
                 :version     "0.1"}

 :global-opts [{  :option  "priip"
                  :as      "The Uccx primary IP addr."
                  :type    :string
                :default "localhost"}
               {  :option  "secip"
                  :as      "The Uccx secondary IP addr."
                  :type    :string
                :default "localhost"}
               ]
 :commands    [{:command     "master" :short "m"
                :description "Polls the UCCX's via http and checks which is master"
                :runs        check-master}
               {:command     "rtstats"  :short "s"
                :description  ["<fetchs the UCCX's RealTime ICD Statistics"
                               ""
                               "Looks great, doesn't it?"]
                :opts        [{:option "priname" :short "pn" :as "Primary UCCX name" :type :string :default "ateadev_uccx"}
                              {:option "wallpwd" :short "wp" :as "Wallboard User password" :type :string :default "ateasystems0916"}]
                :runs        get-rtstats}]})



(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  (run-cmd args CONFIGURATION))
