(ns catacumba.handlers-tests
  (:require [clojure.core.async :refer [put! take! chan <! >! go close!
                                        go-loop onto-chan timeout <!!] :as a]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :as async]
            [clj-http.client :as client]
            [buddy.sign.jwt :as jwt]
            [buddy.sign.jwe :as jwe]
            [buddy.core.hash :as hash]
            [slingshot.slingshot :refer [try+]]
            [cheshire.core :as json]
            [catacumba.core :as ct]
            [catacumba.http :as http]
            [catacumba.handlers.session :as cses]
            [catacumba.handlers.security :as sec]
            [catacumba.handlers.parse :as parse]
            [catacumba.handlers.auth :as cauth]
            [catacumba.handlers.restful :as rest]
            [catacumba.handlers.misc :as misc]
            [catacumba.testing :as test-utils :refer [with-server]]
            [catacumba.core-tests :refer [base-url]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Body params parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest body-parsing
  (testing "Explicit body parsing with multipart request with multiple files."
    (let [p (promise)
          handler (fn [context]
                    (let [form (ct/get-formdata context)]
                      (deliver p form)
                      "hello world"))]
      (with-server {:handler handler}
        (let [multipart {:multipart [{:name "foo" :content "bar"}
                                     {:name "myfile"
                                      :content (-> (io/resource "public/test.txt")
                                                   (io/file))
                                      :encoding "UTF-8"
                                      :mime-type "text/plain"}
                                     {:name "myfile"
                                      :content (-> (io/resource "public/test.txt")
                                                   (io/file))
                                      :encoding "UTF-8"
                                      :mime-type "text/plain"}]}
              response (client/post base-url multipart)]
          (is (= (:status response) 200))
          (is (= (:body response) "hello world"))
          (let [formdata (deref p 1000 nil)]
            (is (= (get formdata :foo) "bar")))))))

  (testing "Form data with more than 8 fields"
    (let [p (promise)
          handler (fn [context]
                    (let [form-dt (ct/get-formdata context)]
                      (deliver p form-dt)
                      "OK"))]
      (with-server {:handler handler}
        (let [res (client/post base-url
                               {:form-params {:a 1, :b 2, :c 3, :d 4, :e 5
                                              :f 6, :g 7, :h 8, :i 9, :j 10
                                              :k 11 :l 12 :m 13 :n 14 :o 15}})]
          (is (= (:status res) 200))
          (is (= (:body res) "OK"))
          (let [form-dt (deref p 1000 nil)]
            (is (false? (empty? form-dt)))
            (is (= 15 (count (keys form-dt))))
            (is (contains? form-dt :a)) 
            (is (contains? form-dt :b))
            (is (contains? form-dt :i))
            (is (contains? form-dt :j)))))))

  (testing "Form encoded body parsing using chain handler"
    (let [p (promise)
          app (ct/routes [[:any (parse/body-params)]
                          [:any #(do
                                   (deliver p (:data %))
                                   "hello world")]])]
      (with-server {:handler app}
        (let [response (client/post base-url {:form-params {:foo "bar"}})]
          (is (= {:foo "bar"} (deref p 1000 nil)))))))

  (testing "Json encoded body parsing using chain handler"
    (let [p (promise)
          app (ct/routes [[:any (parse/body-params)]
                          [:any #(do
                                   (deliver p (:data %))
                                   "hello world")]])]
      (with-server {:handler app}
        (let [response (client/post base-url {:body (json/generate-string {:foo "bar"})
                                              :content-type "application/json"})]
          (is (= {:foo "bar"} (deref p 1000 nil)))))))

  (testing "Transit + json encoded body parsing using chain handler"
    (let [p (promise)
          app (ct/routes [[:any (parse/body-params)]
                          [:any #(do
                                   (deliver p (:data %))
                                   "hello world")]])]
      (with-server {:handler app}
        (let [response (client/post base-url {:body (test-utils/data->transit {:foo/bar "bar"})
                                              :content-type "application/transit+json"})]
          (is (= {:foo/bar "bar"} (deref p 1000 nil)))))))

  (testing "Transit + msgpack encoded body parsing using chain handler"
    (let [p (promise)
          app (ct/routes [[:any (parse/body-params)]
                          [:any #(do
                                   (deliver p (:data %))
                                   "hello world")]])]
      (with-server {:handler app}
        (let [response (client/post base-url {:body (test-utils/data->transit {:foo/bar "bar"} :msgpack)
                                              :content-type "application/transit+msgpack"})]
          (is (= {:foo/bar "bar"} (deref p 1000 nil)))))))


  (testing "Regression: do not throw exception when get request is received with content type header"
    (let [p (promise)
          app (ct/routes [[:any (parse/body-params)]
                          [:any #(do
                                   (deliver p (:data %))
                                   "hello world")]])]
      (with-server {:handler app}
        (let [response (client/get base-url {:content-type "application/transit+json"})]
          (is (= nil (deref p 1000 :nothing)))))))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CSRF
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest csrf-protect-tests
  (testing "Post with csrf using form param"
    (let [p (promise)
          app (ct/routes [[:any (sec/csrf-protect)]
                          [:any (fn [context]
                                  (deliver p (:catacumba.handlers.security/csrftoken context))
                                  "hello world")]])]
      (with-server {:handler app}
        (let [response (client/get base-url {:form-params {:foo "bar"
                                                           :csrftoken "baz"}
                                             :cookies {:csrftoken {:value "baz"}}})]
          (is (= (:status response) 200))
          (is (= (deref p 1000 nil) "baz"))))))

  (testing "Post with csrf using header"
    (let [app (ct/routes [[:any (sec/csrf-protect)]
                          [:any (fn [context] "hello world")]])]
      (with-server {:handler app}
        (let [response (client/get base-url {:form-params {:foo "bar"}
                                             :headers {:x-csrftoken "baz"}
                                             :cookies {:csrftoken {:value "baz"}}})]
          (is (= (:status response) 200))))))

  (testing "Post without csrf"
    (let [app (ct/routes [[:any (sec/csrf-protect)]
                          [:any (fn [context] "hello world")]])]
      (with-server {:handler app}
        (let [response (client/get base-url)]
          (is (= (:status response) 200)))
        (try+
         (let [response (client/post base-url {:form-params {:foo "bar"}})]
           (is (= (:status response) 400)))
         (catch [:status 400] {:keys [status]}
           (is (= status 400))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CORS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cors-config1 {:origin "*"
                   :allow-headers ["X-FooBar"]
                   :max-age 3600})

(def cors-config2 {:origin #{"http://localhost/"}})

(deftest cors-handler
  (testing "Simple cors request"
    (let [handler (fn [ctx] "hello world")
          handler (ct/routes [[:any (misc/cors cors-config1)]
                              [:get handler]])]
      (with-server {:handler handler}
        (let [response (client/get base-url {:headers {:origin "http://localhost/"}})
              headers (:headers response)]
          (is (= (:body response) "hello world"))
          (is (= (:status response) 200))
          (is (= (get headers "access-control-allow-origin") "*"))
          (is (= (get headers "access-control-allow-headers") "x-foobar"))))))

  (testing "Options cors request"
    (let [handler (fn [ctx] "hello world")
          handler (ct/routes [[:any (misc/cors cors-config1)]
                              [:get handler]])]
      (with-server {:handler handler}
        (let [response (client/options base-url {:headers {:origin "http://localhost/"
                                                           :access-control-request-method "post"}})
              headers (:headers response)]
          (is (= (:body response) ""))
          (is (= (:status response) 200))
          (is (= (get headers "access-control-allow-origin") "*"))
          (is (= (get headers "access-control-max-age") "3600"))))))

  (testing "Wrong cors request"
    (let [handler (fn [ctx] "hello world")
          handler (ct/routes [[:any (misc/cors cors-config2)]
                              [:get handler]])]
      (with-server {:handler handler}
        (let [response (client/options base-url {:headers {"Origin" "http://localhast/"
                                                           "access-control-request-method" "post"}})
              headers (:headers response)]
          (is (= (:body response) ""))
          (is (= (:status response) 200))
          (is (= (get headers "access-control-allow-origin") nil))
          (is (= (get headers "access-control-max-age") nil))))))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Session tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest session-handler-with-inmemmory-storage
  (testing "Simple session access in memory storage"
    (let [p (promise)
          storage (cses/memory-storage)
          handler1 (fn [ctx]
                    (let [session (:session ctx)]
                      (swap! session assoc :foo 2)
                      "hello world"))
          handler2 (fn [ctx]
                    (let [session (:session ctx)]
                      (swap! session update :foo inc)
                      "hello world"))
          handler3 (fn [ctx]
                    (let [session (:session ctx)]
                      (deliver p @session)
                      "hello world"))
          app (ct/routes [[:any (cses/session {:storage storage})]
                          [:get "h1" handler1]
                          [:get "h2" handler2]
                          [:get "h3" handler3]])]
      (with-server {:handler app}
        (let [response (client/get (str base-url "/h1"))
              cookie (get-in response [:cookies "sessionid"])]
          (is (= (:status response) 200))
          (let [response (client/get (str base-url "/h2") {:cookies {"sessionid" cookie}})]
            (is (= (:status response) 200)))
          (let [response (client/get (str base-url "/h3") {:cookies {"sessionid" cookie}})]
            (is (= (:status response) 200)))
          (let [data (deref p 1000 nil)]
            (is (= (:foo data) 3)))))
      ))

  (testing "Inject not existing key"
    (let [p (promise)
          storage (cses/memory-storage)
          handler (fn [ctx]
                    "hello world")
          app (ct/routes [[:any (cses/session {:storage storage})]
                          [:get "h1" handler]])]
      (with-server {:handler app}
        (let [response (client/get (str base-url "/h1")
                                   {:cookies {"sessionid" {:value "foobar"}}})
              cookie (get-in response [:cookies "sessionid"])]
          (is (= (:status response) 200))
          (is (not= (:value cookie) "foobar"))))))

  (testing "Remove empty session"
    (let [p (promise)
          storage (cses/memory-storage)
          handler1 (fn [ctx]
                    (let [session (:session ctx)]
                      (swap! session assoc :foo 2)
                      "hello world"))
          handler2 (fn [ctx]
                    (let [session (:session ctx)]
                      (swap! session dissoc :foo)
                      "hello world"))
          app (ct/routes [[:any (cses/session {:storage storage})]
                          [:get "h1" handler1]
                          [:get "h2" handler2]])]
      (with-server {:handler app}
        (let [response (client/get (str base-url "/h1"))
              cookie (get-in response [:cookies "sessionid"])]
          (is (= (:status response) 200))
          (let [response (client/get (str base-url "/h2")
                                     {:cookies {"sessionid" cookie}})]
            (is (= (:status response) 200))
            (is (empty? @storage)))))
      )))

(deftest session-object-interface
  (let [s (#'cses/->session "foobar")]
    (is (not (cses/-accessed? s)))
    (is (not (cses/-modified? s)))
    (is (cses/-empty? s)))

  (let [s (#'cses/->session "foobar")]
    (deref s)
    (is (cses/-accessed? s))
    (is (not (cses/-modified? s)))
    (is (cses/-empty? s)))

  (let [s (#'cses/->session "foobar")]
    (swap! s assoc :foo 2)
    (is (cses/-accessed? s))
    (is (cses/-modified? s))
    (is (not (cses/-empty? s)))))

(deftest session-handler-with-signed-cookie-storage
  (let [p (promise)
        storage (cses/signed-cookie :key "test")
        handler1 (fn [ctx]
                   (let [session (:session ctx)]
                     (swap! session assoc :foo 2)
                     "hello world"))
        handler2 (fn [ctx]
                   (let [session (:session ctx)]
                     (swap! session update :foo inc)
                     "hello world"))
        handler3 (fn [ctx]
                   (let [session (:session ctx)]
                     (deliver p @session)
                     "hello world"))
        app (ct/routes [[:any (cses/session {:storage storage})]
                        [:get "h1" handler1]
                        [:get "h2" handler2]
                        [:get "h3" handler3]])]
    (with-server {:handler app}
      (let [response (client/get (str base-url "/h1"))
            cookie (get-in response [:cookies "sessionid"])]
        (is (= (:status response) 200))
        (let [response (client/get (str base-url "/h2") {:cookies {"sessionid" cookie}})
              cookie (get-in response [:cookies "sessionid"])]
          (is (= (:status response) 200))
          (let [response (client/get (str base-url "/h3") {:cookies {"sessionid" cookie}})]
            (is (= (:status response) 200))
            (let [data (deref p 1000 nil)]
              (is (= (:foo data) 3)))))))
    ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (deftest interceptors-tests
;;   (let [counter (atom 0)
;;         p1 (promise)]
;;     (letfn [(interceptor [_ type continuation]
;;               (swap! counter inc)
;;               (continuation)
;;               (swap! counter inc))
;;             (handler2 [context]
;;               (ct/delegate context))
;;             (handler3 [context]
;;               (deliver p1 nil)
;;               "hello world")]
;;       (with-server {:handler (ct/routes [[:interceptor interceptor]
;;                                          [:any handler2]
;;                                          [:any handler3]])}
;;         (let [response (client/get base-url)]
;;           (is (nil? (deref p1 1000 nil)))
;;           (is (= (:body response) "hello world"))
;;           (is (= (:status response) 200))
;;           (is (= @counter 2)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Auth
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def jws-secret "secret")
(def jwe-secret (hash/sha256 jws-secret))

(def jws-backend (cauth/jws-backend {:secret jws-secret}))
(def jwe-backend (cauth/jwe-backend {:secret jwe-secret}))

(deftest auth-tests
  (testing "JWS"
    (letfn [(handler [context]
              (if (:identity context)
                (http/ok "Identified")
                (http/unauthorized "Unauthorized")))]
      (with-server {:handler (ct/routes [[:auth jws-backend]
                                         [:any handler]])}
        (try+
         (let [response (client/get base-url)]
           (is (= (:status response) 401)))
         (catch Object e
           (is (= (:status e) 401))))

        (let [token (jwt/sign {:userid 1} jws-secret)
              headers {"Authorization" (str "Token " token)}
              response (client/get base-url {:headers headers})]
          (is (= (:status response) 200))))))

  (testing "JWE"
    (letfn [(handler [context]
              (if (:identity context)
                (http/ok "Identified")
                (http/unauthorized "Unauthorized")))]
      (with-server {:handler (ct/routes [[:auth jwe-backend]
                                         [:any handler]])}
        (try+
         (let [response (client/get base-url)]
           (is (= (:status response) 401)))
         (catch Object e
           (is (= (:status e) 401))))

        (let [token (jwt/encrypt {:userid 1} jwe-secret)
              headers {"Authorization" (str "Token " token)}
              response (client/get base-url {:headers headers})]
          (is (= (:status response) 200)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Restful
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sample-method
  ([code] (sample-method code ""))
  ([code body]
   (fn [context]
     (http/response body code))))

(def simple-resource-1
  {:index (sample-method 200 "1")
   :show (sample-method 200 "2")
   :create (sample-method 200 "3")
   :update (sample-method 200 "4")
   :delete (sample-method 200 "5")})

(def simple-resource-2
  {:show (sample-method 200 "2")})

(deftest restful-resources-tests
  (testing "Resource with all methods"
    (let [app (ct/routes [[:restful/resource "foo" simple-resource-1]])]
      (with-server {:handler app}
        (let [response (client/get (str base-url "/foo"))]
          (is (= (:body response) "1"))
          (is (= (:status response) 200)))
        (let [response (client/post (str base-url "/foo"))]
          (is (= (:body response) "3"))
          (is (= (:status response) 200)))
        (let [response (client/get (str base-url "/foo/1"))]
          (is (= (:body response) "2"))
          (is (= (:status response) 200)))
        (let [response (client/put (str base-url "/foo/1"))]
          (is (= (:body response) "4"))
          (is (= (:status response) 200)))
        (let [response (client/delete (str base-url "/foo/1"))]
          (is (= (:body response) "5"))
          (is (= (:status response) 200)))
        (try
          (client/put (str base-url "/foo"))
          (throw (Exception. "unexpected"))
          (catch clojure.lang.ExceptionInfo e
            (let [response (ex-data e)]
              (is (= (:status response) 405)))))
        (try
          (client/delete (str base-url "/foo"))
          (throw (Exception. "unexpected"))
          (catch clojure.lang.ExceptionInfo e
            (let [response (ex-data e)]
              (is (= (:status response) 405)))))
        )))

  (testing "Resource with some methods"
    (let [app (ct/routes [[:restful/resource "foo" simple-resource-2]])]
      (with-server {:handler app}
        (let [response (client/get (str base-url "/foo/1"))]
          (is (= (:body response) "2"))
          (is (= (:status response) 200)))
        (try
          (client/get (str base-url "/foo"))
          (throw (Exception. "unexpected"))
          (catch clojure.lang.ExceptionInfo e
            (let [response (ex-data e)]
              (is (= (:status response) 405)))))
        (try
          (client/delete (str base-url "/foo"))
          (throw (Exception. "unexpected"))
          (catch clojure.lang.ExceptionInfo e
            (let [response (ex-data e)]
              (is (= (:status response) 405)))))
        ))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-logged [handler f]
  (let [p   (promise)
        app (ct/routes [[:any (misc/log #(deliver p %&))]
                        [:get handler]])]
    (with-server {:handler app}
      (f p))))

(deftest logging-tests
  (testing "Custom log function"
    (with-logged (constantly (http/ok "" {:x "hi"}))
      (fn [p]
        (client/get base-url)
        (let [[context {:keys [headers] :as outcome}] (deref p 1000 nil)]
          (is (= "/" (:path context)))
          (is (= "hi" (:x headers)))))))
  (testing "Custom log function w/ error"
    (with-logged (fn [_] (throw (Exception. "oops")))
      (fn [p]
        (try+
          (client/get base-url)
          (catch [:status 500] _))
        (let [[_ {{:keys [code]} :status}] (deref p 1000 nil)]
          (is (= 500 code))))))
  (testing "Default log function"
    (with-server {:handler (ct/routes [[:any (misc/log)]
                                       [:get (constantly "")]])}
      ;; Having it not explode is about the best we can do
      (client/get base-url))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CPS handler type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest cps-handler-type
  (let [handler (fn [context next]
                   (a/thread
                     (a/<!! (a/timeout 500))
                     (next "hello world cps")))]
    (with-server {:handler (with-meta handler {:handler-type :catacumba/cps})}
      (let [response (client/get base-url)]
        (is (= (:body response) "hello world cps"))
        (is (= (:status response) 200))))))



