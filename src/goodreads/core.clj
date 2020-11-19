(ns goodreads.core
  (:gen-class)
  (:require [clojure.tools.cli :as cli]
            [manifold.deferred :as d]))

;; TODO: this implementation is pretty useless :(
(defn build-recommentations [_]
  (d/success-deferred
   [{:title "My Side of the Mountain (Mountain, #1)"
     :authors [{:name "Jean Craighead George"}]
     :link "https://www.goodreads.com/book/show/41667.My_Side_of_the_Mountain"}
    {:title "Incident at Hawk's Hill"
     :authors [{:name "Allan W. Eckert"}]
     :link "https://www.goodreads.com/book/show/438131.Incident_at_Hawk_s_Hill"}
    {:title "The Black Pearl"
     :authors [{:name "Scott O'Dell"}]
     :link "https://www.goodreads.com/book/show/124245.The_Black_Pearl"}]))

(def cli-options [["-t"
                   "--timeout-ms"
                   "Wait before finished"
                   :default 5000
                   :parse-fn #(Integer/parseInt %)]
                  ["-n"
                   "--number-books"
                   "How many books do you want to recommend"
                   :default 10
                   :parse-fn #(Integer/parseInt %)]
                  ["-h" "--help"]])

(defn book->str [{:keys [title link authors]}]
  (format "\"%s\" by %s\nMore: %s"
          title
          (->> authors
               (map :name)
               (clojure.string/join ", "))
          link))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (contains? options :help) (do (println summary) (System/exit 0))
      (some? errors) (do (println errors) (System/exit 1))
      (empty? args) (do (println "Please, specify user's token") (System/exit 1))
      :else (let [config {:token (first args)}
                  books (-> (build-recommentations config)
                            (d/timeout! (:timeout-ms options) ::timeout)
                            deref)]
              (cond
                (= ::timeout books) (println "Not enough time :(")
                (empty? books) (println "Nothing found, leave me alone :(")
                :else (doseq [[i book] (map-indexed vector books)]
                        (println (str "#" (inc i)))
                        (println (book->str book))
                        (println)))))))
