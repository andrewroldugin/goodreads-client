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

(defn read-config [file]
  "Reads edn config file"
  (-> file (slurp) (edn/read-string)))

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

(defn get-books-xml [config]
  "Returns books xml string"
  (oauth-http-get config
                  "https://www.goodreads.com/review/list"
                  {:v 2 :key (:api-key config) :format "xml" :per_page 200}))

(defn xml->books [s shelf]
  "Returns list of book ids from xml for specified shelf"
  (let [z (-> s (xml-parse-str) (zip/xml-zip))
        reviews (xml-> z :GoodreadsResponse :reviews :review [:shelves :shelf (attr= :name shelf)])]
    (map #(-> % (xml1-> :review :book :id text) (edn/read-string)) reviews)))

;; (def xml-str (get-books-xml (read-config "config.edn")))
;; (xml->books xml-str "read")
;; (xml->books xml-str "currently-reading")

(defn get-book-xml [config book-id]
  "Returns book xml string by book id"
  (oauth-http-get config
                  "https://www.goodreads.com/book/show"
                  {:id book-id :key (:api-key config) :format "xml"}))

;; (def config (read-config "config.edn"))
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
  "Returns top by average rating similar books of read books
 excluding books that are currently reading."
  (d/future
    (let [books-xml (get-books-xml config)
          books-read (xml->books books-xml "read")
          books-reading (into #{} (xml->books books-xml "currently-reading"))]
      (reduce
       (fn [acc x]
         (take number-books
               (->> (get-similar-books config x)
                    (remove #(books-reading (:id %)))
                    (concat acc)
                    (distinct)
                    (sort-by :average-rating >))))
       []
       books-read))))

;; @(build-recommendations (read-config "config.edn") 10)

(def cli-options [["-t"
                   "--timeout-ms"
                   "Wait before finished"
                   :default 10000
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
      (empty? args) (do (println "Please, specify config file with user's token") (System/exit 1))
      :else (let [config (read-config (first args))
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
