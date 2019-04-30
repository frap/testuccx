(defproject testuccx "0.1.1-SNAPSHOT"
  :description "Quick test of UCCX connectivity"
  :url "http://ateasystems.com/testuccx"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/spec.alpha "0.2.176"]
                 [cli-matic "0.3.6"]
                 [aleph "0.4.7-alpha5"]
                 [byte-streams "0.2.5-alpha2"]
                 [manifold "0.1.9-alpha3"]
                 [io.aviso/pretty "0.1.37"]
                 [fipp "0.6.17"]
                 [com.ibm.informix/jdbc "4.50.1"]
                 [org.clojure/core.async "0.4.490"]
                 [org.clojure/java.jdbc "0.7.0"]
                 [hikari-cp "2.7.1"]
                 [clojure-lanterna "0.9.7"]
                 [dire "0.5.4"]
                 [tick "0.4.12-alpha"]
                 [org.clojure/tools.logging "0.4.1"]
                 [ch.qos.logback/logback-classic "1.1.3"]
                 ]
   :repl-options {:init-ns testuccx.core}
   :main testuccx.core
  :aot :all)
