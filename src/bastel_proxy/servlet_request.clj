(ns bastel_proxy.servlet_request
  (:import (javax.servlet.http HttpServletRequest)
           (java.util Locale Collections)))
(defn to-upper [str]
  (.toUpperCase str Locale/ENGLISH))
(defn to-lower [str]
  (.toLowerCase str Locale/ENGLISH))


(defn ring->servlet-request
  "The reverse of ring.util.servlet/build-request-map. Takes a Ring request and builds a HttpServletRequest out of it"
  [ring-request original-request]
  (proxy [HttpServletRequest] []
    (getRequestURI [] (:uri ring-request))
    (getRequestURL [] (doto (StringBuffer.)
                        (.append (name (:scheme ring-request)))
                        (.append "://")
                        (.append (get (:headers ring-request) "host"))
                        (.append (:uri ring-request))))
    (getQueryString [] (:query-string ring-request))
    (getMethod [] (some-> (:request-method ring-request) name to-upper))
    (getHeader [name] (get (:headers ring-request) (to-lower name)))
    (getHeaders [name] (Collections/enumeration [(get (:headers ring-request) (to-lower name))]))
    (getHeaderNames [] (Collections/enumeration (keys (:headers ring-request))))
    (getProtocol [] (:protocol ring-request))
    (getRemoteAddr [] (:remote-addr ring-request))
    (getScheme [] (name (:scheme ring-request)))
    (getContentLength [] (or (:content-length ring-request) -1))
    (getContentType [] (:content-type ring-request))
    (getInputStream [] (:body ring-request))
    (getLocalName [] (.getLocalName original-request))
    (startAsync [] (.startAsync original-request))
    (getAttribute [name] (.getAttribute original-request name))
    (getServletPath [] (.getServletPath original-request))
    ; Sloppy, afaik should be relative to servlet path thing? Good enough for a start
    (getPathInfo [] (:uri ring-request))
    (getDateHeader [name] (.getDateHeader original-request name))
    )
  )

(defn replace-request [ring-request original-request]
  (if (empty? ring-request)
    original-request
    (ring->servlet-request ring-request original-request)))