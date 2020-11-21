(ns goodreads.core
  (:gen-class)
  (:use [clojure.data.zip.xml])
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [manifold.deferred :as d]
            [clojure.edn :as edn]
            [oauth.client :as oauth]
            [clj-http.client :as http]
            [clojure.xml :as xml]
            [clojure.zip :as zip]))

;; (def config (edn/read-string (slurp "config.edn")))

;; (build-recommentations config)
;; (do
;;   (build-recommentations config)
;;   (build-recommentations config)
;;   (build-recommentations config)
;;   (build-recommentations config)
;;   (build-recommentations config)
;;   (build-recommentations config)
;;   (build-recommentations config)
;;   (build-recommentations config)
;;   )

(defn make-consumer [api-key api-secret]
  (oauth/make-consumer api-key
                       api-secret
                       "https://www.goodreads.com/oauth/request_token"
                       "https://www.goodreads.com/oauth/access_token"
                       "https://www.goodreads.com/oauth/authorize"
                       :hmac-sha1))

(defn oauth-http-get [config request-url user-params]
  (let [consumer (make-consumer (:api-key config) (:api-secret config))
        credentials (oauth/credentials consumer
                                       (:oauth-token config)
                                       (:oauth-token-secret config)
                                       :GET
                                       request-url
                                       user-params)
        query-params (merge credentials user-params)
        response (http/get request-url {:query-params query-params})]
    (when (= (:status response) 200)
      (:body response))))

(defn xml-parse-str [str]
  (-> str (.getBytes) (io/input-stream) (xml/parse)))

(defn xml-get-user-id [str]
  "Returns user id from xml string"
  (let [z (-> str (xml-parse-str) (zip/xml-zip))]
    (xml1-> z :GoodreadsResponse :user (attr :id))))

;; (def str (oauth-http-get config "https://www.goodreads.com/api/auth_user" {}))
;; (xml-get-user-id str)

(defn get-user-id [config]
  "Returns user id"
  (-> (oauth-http-get config "https://www.goodreads.com/api/auth_user" {})
      (xml-get-user-id)))

;; (get-user-id config)

;; TODO: this implementation is pretty useless :(
(defn build-recommentations [{:keys [api-key api-secret oauth-token oauth-token-secret]}]
  (let [consumer (make-consumer api-key api-secret)
        user-params {:v 2 :id 124723493 :key api-key :format "xml"}
        credentials (oauth/credentials consumer
                                       oauth-token
                                       oauth-token-secret
                                       :GET
                                       "https://www.goodreads.com/review/list"
                                       user-params)
        response (http/get "https://www.goodreads.com/review/list"
                           {:query-params (merge credentials user-params)})]
    (println ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    (pprint (:body response))
    )
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
      :else (let [config (-> (first args) (slurp) (edn/read-string))
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
