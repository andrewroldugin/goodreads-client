(ns goodreads.core
  (:gen-class)
  (:use [clojure.data.zip.xml])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [oauth.client :as oauth]
            [clj-http.client :as http]
            [manifold.deferred :as d]))

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
;; (def xml-str (oauth-http-get config "https://www.goodreads.com/api/auth_user" {}))
;; (println xml-str)
;; (xml-get-user-id xml-str)
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

;; (def config (edn/read-string (slurp "config.edn")))
;; (do (def user-id (get-user-id config)) user-id)
;; (def xml-str (get-books-xml config user-id "currently-reading"))
;; (xml-get-books xml-str)
;; (def xml-str (get-books-xml config user-id "read"))
;; (xml-get-books xml-str)
;; (def xml-str (get-books-xml config (get-user-id config) "read"))
;; (xml-get-books xml-str)

(defn get-books [config user-id shelf]
  "Returns book ids for specified user and shelf"
  (-> config (get-books-xml user-id shelf) (xml-get-books)))

;; (def config (edn/read-string (slurp "config.edn")))
;; (get-books config (get-user-id config) "read")

(defn get-book-xml [config book-id]
  "Returns book xml string by book id"
  (oauth-http-get config
                  "https://www.goodreads.com/book/show"
                  {:id book-id :key (:api-key config) :format "xml"}))

;; (def config (edn/read-string (slurp "config.edn")))
;; (do (def book-id (first (get-books config (get-user-id config) "read"))) book-id)
;; (def xml-str (get-book-xml config book-id))

(defn xml->similar-books [book-xml]
  (let [z (-> book-xml (xml-parse-str) (zip/xml-zip))]
    (vec (for [similar-book (xml-> z :GoodreadsResponse :book :similar_books :book)]
           {:id (edn/read-string (xml1-> similar-book :id text))
            :title (xml1-> similar-book :title text)
            :link (xml1-> similar-book :link text)
            :average-rating (edn/read-string (xml1-> similar-book :average_rating text))
            :authors (vec (for [author (xml-> similar-book :authors :author)]
                            {:name (xml1-> author :name text)}))}))))

;; (xml->similar-books xml-str)

(defn get-similar-books [config book-id]
  (-> config (get-book-xml book-id) (xml->similar-books)))

;; (get-similar-books config book-id)

(defn build-recommendations [config number-books]
  (d/success-deferred
   (let [user-id (get-user-id config)
         books-read (get-books config user-id "read")
         books-reading (into #{} (get-books config user-id "currently-reading"))]
     (->> books-read
          (mapcat (partial get-similar-books config))
          (sort-by :average-rating >)
          (distinct)
          (remove #(books-reading (:id %)))
          (take number-books)))))

;; (def config (edn/read-string (slurp "config.edn")))
;; (build-recommendations config 36)

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
                  books (-> (build-recommendations config (:number-books options))
                            (d/timeout! (:timeout-ms options) ::timeout)
                            deref)]
              (cond
                (= ::timeout books) (println "Not enough time :(")
                (empty? books) (println "Nothing found, leave me alone :(")
                :else (doseq [[i book] (map-indexed vector books)]
                        (println (str "#" (inc i)))
                        (println (book->str book))
                        (println)))))))
