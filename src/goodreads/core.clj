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

(defn parse-int [number-string]
  "Returns integer from string if possible otherwise nil"
  (try (Integer/parseInt number-string)
       (catch Exception e nil)))

(defn xml-parse-str [str]
  "Parses xml string"
  (-> str (.getBytes) (io/input-stream) (xml/parse)))

(defn make-consumer [api-key api-secret]
  "Makes OAuth v1 consumer"
  (oauth/make-consumer api-key
                       api-secret
                       "https://www.goodreads.com/oauth/request_token"
                       "https://www.goodreads.com/oauth/access_token"
                       "https://www.goodreads.com/oauth/authorize"
                       :hmac-sha1))

(defn oauth-http-get [config request-url user-params]
  "Makes http GET request with OAuth v1 authorization"
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

(defn get-user-xml [config]
  "Returns user xml"
  (oauth-http-get config "https://www.goodreads.com/api/auth_user" {}))

(defn xml-get-user-id [xml-str]
  "Returns user id from user xml string"
  (let [z (-> xml-str (xml-parse-str) (zip/xml-zip))]
    (xml1-> z :GoodreadsResponse :user (attr :id))))

;; (def config (edn/read-string (slurp "config.edn")))
;; (def str (oauth-http-get config "https://www.goodreads.com/api/auth_user" {}))
;; (println str)
;; (xml-get-user-id str)
;; (get-user-id config)

(defn get-user-id [config]
  "Returns user id"
  (-> config (get-user-xml) (xml-get-user-id)))

(defn get-books-xml [config user-id shelf]
  "Returns books xml string for specified user and shelf"
  (oauth-http-get config
                  "https://www.goodreads.com/review/list"
                  {:v 2 :id user-id :key (:api-key config) :format "xml" :shelf shelf}))

(defn xml-get-books [xml-str]
  "Returns list of book ids from xml"
  (let [z (-> xml-str (xml-parse-str) (zip/xml-zip))
        ids (xml-> z :GoodreadsResponse :reviews :review :book :id text)]
    (map parse-int ids)))

(defn get-books [config user-id shelf]
  "Returns book ids for specified user and shelf"
  (-> config (get-books-xml user-id shelf) (xml-get-books)))

;; (def config (edn/read-string (slurp "config.edn")))
;; (do (def user-id (get-user-id config)) user-id)
;; (def xml-str (get-books-xml config user-id "currently-reading"))
;; (xml-get-books xml-str)
;; (def xml-str (get-books-xml config user-id "read"))
;; (xml-get-books xml-str)
;; (def xml-str (get-books-xml config (get-user-id config) "read"))
;; (xml-get-books xml-str)
;; (get-books config (get-user-id config) "read")

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
