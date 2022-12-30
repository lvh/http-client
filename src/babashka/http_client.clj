(ns babashka.http-client
  (:refer-clojure :exclude [send get])
  (:require [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http
            HttpClient
            HttpClient$Builder
            HttpClient$Redirect
            HttpClient$Version
            HttpRequest
            HttpRequest$BodyPublishers
            HttpRequest$Builder
            HttpResponse
            HttpResponse$BodyHandlers]
           [java.util.concurrent CompletableFuture]
           [java.util.function Function Supplier]
           [java.util Base64]
           [java.time Duration]))

(defn- ->follow-redirect [redirect]
  (case redirect
    :always HttpClient$Redirect/ALWAYS
    :never HttpClient$Redirect/NEVER
    :normal HttpClient$Redirect/NORMAL))

(defn- version-keyword->version-enum [version]
  (case version
    :http1.1 HttpClient$Version/HTTP_1_1
    :http2   HttpClient$Version/HTTP_2))

(defn client-builder
  (^HttpClient$Builder []
   (client-builder {}))
  (^HttpClient$Builder [opts]
   (let [{:keys [connect-timeout
                 cookie-handler
                 executor
                 follow-redirects
                 priority
                 proxy
                 ssl-context
                 ssl-parameters
                 version]} opts]
     (cond-> (HttpClient/newBuilder)
       ;; connect-timeout  (.connectTimeout (util/convert-timeout connect-timeout))
       cookie-handler   (.cookieHandler cookie-handler)
       executor         (.executor executor)
       follow-redirects (.followRedirects (->follow-redirect follow-redirects))
       priority         (.priority priority)
       proxy            (.proxy proxy)
       ssl-context      (.sslContext ssl-context)
       ssl-parameters   (.sslParameters ssl-parameters)
       version          (.version (version-keyword->version-enum version))))))

(defn client
  (^HttpClient [] (.build (client-builder)))
  (^HttpClient [opts]
   (if (map? opts)
     (.build (client-builder opts))
     (.build ^HttpClient$Builder opts))))

(def ^HttpClient default-client
  (delay (client {:follow-redirects :always})))

(defn- method-keyword->str [method]
  (str/upper-case (name method)))

(defn- coerce-key
  "Coerces a key to str"
  [k]
  (if (keyword? k)
    (-> k str (subs 1))
    (str k)))

(defn ^:private coerce-headers
  [headers]
  (mapcat
   (fn [[k v]]
     (if (sequential? v)
       (interleave (repeat (coerce-key k)) v)
       [(coerce-key k) v]))
   headers))

(defn- input-stream-supplier [s]
  (reify Supplier
    (get [_this] s)))

(defn- ->body-publisher [body]
  (cond
    (nil? body)
    (HttpRequest$BodyPublishers/noBody)

    (string? body)
    (HttpRequest$BodyPublishers/ofString body)

    (instance? java.io.InputStream body)
    (HttpRequest$BodyPublishers/ofInputStream (input-stream-supplier body))

    (bytes? body)
    (HttpRequest$BodyPublishers/ofByteArray body)

    (instance? java.io.File body)
    (HttpRequest$BodyPublishers/ofString (slurp body))

    :else
    (throw (ex-info (str "Don't know how to convert " (type body) "to body")
                    {:body body}))))

(defn- convert-timeout [t]
  (if (integer? t)
    (Duration/ofMillis t)
    t))

(defn- url-encode
  "Returns an UTF-8 URL encoded version of the given string."
  [^String unencoded]
  (URLEncoder/encode unencoded "UTF-8"))

(defn map->query-params [query-params-map]
  (loop [params* (transient [])
         kvs (seq query-params-map)]
    (if kvs
      (let [[k v] (first kvs)]
        (recur (conj! params* (str (url-encode (coerce-key k)) "=" (url-encode (str v)))) (next kvs)))
      (str/join "&" (persistent! params*)))))

(defn map->form-params [form-params-map]
  (loop [params* (transient [])
         kvs (seq form-params-map)]
    (if kvs
      (let [[k v] (first kvs)
            v (url-encode (str v))
            param (str (url-encode (coerce-key k)) "=" v)]
        (recur (conj! params* param) (next kvs)))
      (str/join "&" (persistent! params*)))))

(defn basic-auth-value [x]
  (let [[user pass] (if (sequential? x) x [(clojure.core/get x :user) (clojure.core/get x :pass)])
        basic-auth (str user ":" pass)]
    (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes basic-auth "UTF-8")))))

(defn with-basic-auth [opts]
  (if-let [basic-auth (:basic-auth opts)]
    (let [headers (:headers opts)
          auth (basic-auth-value basic-auth)
          headers (assoc headers :authorization auth)
          opts (assoc opts :headers headers)]
      opts)
    opts))

(defn ->request-builder ^HttpRequest$Builder [opts]
  (let [{:keys [expect-continue
                headers
                method
                timeout
                uri
                version
                body
                form-params]} opts
        ;; TODO: middleware
        uri (if-let [qp (:query-params opts)]
              (str uri "?" (map->query-params qp))
              uri)
        body (if form-params
               (map->form-params form-params)
               body)
        headers (cond (:content-type headers)
                      headers
                      form-params
                      (assoc headers :content-type "application/x-www-form-urlencoded")
                      :else headers)]
    (cond-> (HttpRequest/newBuilder)
      (some? expect-continue) (.expectContinue expect-continue)
      (seq headers)            (.headers (into-array String (coerce-headers headers)))
      method                   (.method (method-keyword->str method) (->body-publisher body))
      timeout                  (.timeout (convert-timeout timeout))
      uri                      (.uri (URI/create uri))
      version                  (.version (version-keyword->version-enum version)))))

(defn ->request
  (^HttpRequest [req-map] (.build (->request-builder (with-basic-auth  req-map)))))

(def ^:private bh-of-string (HttpResponse$BodyHandlers/ofString))
(def ^:private bh-of-input-stream (HttpResponse$BodyHandlers/ofInputStream))
(def ^:private bh-of-byte-array (HttpResponse$BodyHandlers/ofByteArray))

(defn- ->body-handler [mode]
  (case mode
    nil bh-of-string
    :string bh-of-string
    :stream bh-of-input-stream
    :bytes bh-of-byte-array))

(defn- version-enum->version-keyword [^HttpClient$Version version]
  (case (.name version)
    "HTTP_1_1" :http1.1
    "HTTP_2"   :http2))

(defn response->map [^HttpResponse resp]
  {:status (.statusCode resp)
   :body (.body resp)
   :version (-> resp .version version-enum->version-keyword)
   :headers (into {}
                  (map (fn [[k v]] [k (if (= 1 (count v))
                                        (first v)
                                        (vec v))]))
                  (.map (.headers resp)))})

(defn request
  [{:keys [client raw as] :as req}]
  (let [^HttpClient client (or client @default-client)
        req' (->request req)
        resp (.send client req' (->body-handler as))]
    (if raw resp (response->map resp))))

(defn get
  ([uri] (get uri nil))
  ([uri opts]
   (let [opts (assoc opts :uri uri :method :get)]
     (request opts))))

(defn delete
  ([uri] (delete uri nil))
  ([uri opts]
   (let [opts (assoc opts :uri uri :method :delete)]
     (request opts))))

(defn head
  ([uri] (head uri nil))
  ([uri opts]
   (let [opts (assoc opts :uri uri :method :head)]
     (request opts))))

(defn post
  ([uri] (post uri nil))
  ([uri opts]
   (let [opts (assoc opts :uri uri :method :post)]
     (request opts))))

(defn patch
  ([url] (patch url nil))
  ([url opts]
   (let [opts (assoc opts
                     :uri url
                     :method :patch)]
     (request opts))))
