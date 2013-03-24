(defproject triplema "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [java-api "1.8.1.0"]]
  :repositories {"local" ~(str (.toURI (java.io.File. "maven_repo")))}
  :main triplema.core)