(ns macchiato.middleware.restful-format
  (:require
    [cljs.nodejs :as node]
    [cognitect.transit :as t]))

(def concat-stream (node/require "concat-stream"))

(def accepts (node/require "accepts"))

(def ct (node/require "content-type"))

(def default-content-types
  #{"application/json"
    "application/transit"})

(def default-accept-types
  ["application/transit" :json])

(defmulti parse-request-content :type)

(defmethod parse-request-content "application/json"
  [{:keys [charset body keywordize?]}]
  (js->clj (js/JSON.parse body charset) :keywordize-keys keywordize?))

(defmethod parse-request-content "application/transit"
  [{:keys [charset body keywordize?]}]
  (t/read (t/reader :json) body))

(defmulti parse-response-content :type)

(defmethod parse-response-content :json [{:keys [charset body]}]
  (clj->js body))

(defmethod parse-response-content :transit [{:keys [charset body]}]
  (t/write (t/writer :json) body))

(defn infer-request-content-type [headers content-types]
  (let [content-type (.parse ct (get headers "content-type"))]
    (when-let [type (get content-types (.-type content-type))]
      {:type    type
       :charset (or (.-charset content-type) "utf8")})))

(defn infer-response-content-type [body accept-types]
  (some-> (accepts. body) (.type accept-types) keyword))

(defn format-response-body [node-request response accept-types]
  (if-let [accept-type (infer-response-content-type node-request accept-types)]
    (update response :body #(parse-response-content {:type accept-type :body %}))
    response))

(defn
  ^{:macchiato/middleware
    {:id :wrap-restful-format}}
  wrap-restful-format
  "attempts to infer the request content type using the content-type header
   serializes the response based on the first known accept header

   optional keys:
   :content-types a set of strings matching the content types
   :accept-types a vector of accept types to match in the desired order
   :keywordize? a boolean specifying whether to keywordized parsed request"
  [handler & [{:keys [content-types accept-types keywordize?]}]]
  (let [content-types (or content-types default-content-types)
        accept-types  (clj->js (or accept-types default-accept-types))]
    (fn [{:keys [headers body] :as request} respond raise]
      (let [respond (fn [response] (respond (format-response-body body response accept-types)))]
        (if-let [content (infer-request-content-type headers content-types)]
          (if (string? body)
            (assoc request :body (parse-request-content (assoc content :body body :keywordize? keywordize?)))
            (.pipe body
                   (concat-stream.
                     (fn [body]
                       (handler
                         (assoc request :body (parse-request-content (assoc content :body body :keywordize? keywordize?)))
                         respond
                         raise)))))
          (handler request respond raise))))))
