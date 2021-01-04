(ns core.sso-clojure
  (:require
   [cheshire.core :refer [generate-string parse-string]]
   [clj-http.client :as client]
   [clojure.walk :refer [keywordize-keys]]
   [muuntaja.middleware :as muuntaja]
   [reitit.ring :as reitit]
   [ring.adapter.jetty :as jetty]
   [ring.util.http-response :as response]
   [ring.util.response :refer [redirect]]
   [ring.middleware.reload :refer [wrap-reload]]
   [ring.util.codec :as codec]
   [selmer.parser :as selmer]
   [selmer.middleware :refer [wrap-error-page]]
   [taoensso.timbre :as timbre
    :refer [info log debug error warn]]))

(def config
  {:auth-url "http://localhost:8080/auth/realms/sso-test/protocol/openid-connect/auth"
   :logout-url "http://localhost:8080/auth/realms/sso-test/protocol/openid-connect/logout"
   :token-endpoint "http://localhost:8080/auth/realms/sso-test/protocol/openid-connect/token"
   :client-id "billingApp"
   :client-password "fe0a7e01-8b66-4706-8f37-e7d333c29e6f"
   :redirect-uri "http://localhost:3000/auth-code-redirect"
   :landing-page "http://localhost:3000"})

(def app-var (atom {}))

(defn wrap-nocache [handler]
  (fn [request]
    (-> request
        handler
        (assoc-in [:headers "Pragma"] "no-cache"))))

(defn wrap-formats [handler]
  (-> handler
      (muuntaja/wrap-format)))

(defn response-handler [request]
  (response/ok
   (str "<html><body>your IP is: "
        (:remote-addr request)
        "</body></html>")))

(defn home-handler [request]
  (response/ok
   (selmer/render-file "login.html" {:title "~=[λ RuL3z!]=~"
                                     :session (:session-state @app-var)
                                     :code (:code @app-var)
                                     :access-token (get-in @app-var [:token :access_token])
                                     :refresh-token (get-in @app-var [:token :refresh_token])
                                     :scope (get-in @app-var [:token :scope])})))

(defn login-handler [request]
  ;; create a redirect URL for authentication endpoint.
  (let [client_id (:client-id config)
        redirect_uri (:redirect-uri config)
        query-string (client/generate-query-string
                      {:client_id client_id
                       :response_type "code"
                       :redirect_uri redirect_uri})
        auth-url (:auth-url config)]
    (redirect (str auth-url "?" query-string))))

(defn auth-code-redirect [request]
  (info {:query-string (:query-string request)})
  (let [query-params (-> request :query-string codec/form-decode keywordize-keys)
        landing-page (:landing-page config)]
    (swap! app-var assoc :code (:code query-params)
           :session-state (:session_state query-params))
    (redirect landing-page)))

(defn logout-handler [request]
  (let [query-string (client/generate-query-string {:redirect_uri (:landing-page config)})
        logout-url (str (:logout-url config) "?" query-string)]
    (reset! app-var {})
    (redirect logout-url)))

(defn get-token []
  (client/post (:token-endpoint config)
               {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                :basic-auth [(:client-id config) (:client-password config)]
                :form-params {:grant_type "authorization_code"
                              :code (:code @app-var)
                              :redirect_uri (:redirect-uri config)
                              :client_id (:client-id config)}}))

(defn exchange-token-handler [request]
  (let [token (get-token)]
    (swap! app-var assoc :token (-> (:body token) parse-string keywordize-keys))
    (info {:token (:token @app-var)})
    (redirect (:landing-page config))))

(def routes
  [["/" {:get home-handler}]
   ["/login" {:get login-handler}]
   ["/exchange-token" {:get exchange-token-handler}]
   ["/logout" {:get logout-handler}]
   ["/auth-code-redirect" {:get auth-code-redirect}]])

(def handler
  (reitit/routes
   (reitit/ring-handler
    (reitit/router routes))
   (reitit/create-resource-handler
    {:path "/"})
   (reitit/create-default-handler
    {:not-found
     (constantly (response/not-found "404 - Page not found"))
     :method-not-allowed
     (constantly (response/method-not-allowed "405 - Not allowed"))
     :not-acceptable
     (constantly (response/not-acceptable "406 - Not acceptable"))})))

(defn -main []
  (jetty/run-jetty
   (-> #'handler
       wrap-nocache
       wrap-reload)
   {:port 3000
    :join? false}))