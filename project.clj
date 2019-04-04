(defproject testuccx "0.1.0-SNAPSHOT"
  :description "Quick test of UCCX connectivity"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [cli-matic "0.3.6"]
                 [clj-http "3.9.1"]
                 [funcool/promesa "2.0.1"]
                 [funcool/httpurr "1.1.0"]
                 [aleph "0.4.7-alpha5"]
                 [io.aviso/pretty "0.1.37"]
                 [com.ibm.informix/jdbc "4.50.1"]
                 ]
  :repl-options {:init-ns testuccx.core}
   :main testuccx.core
  :aot :all)
