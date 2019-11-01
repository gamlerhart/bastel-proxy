(ns bastel-proxy.certs
  (:require [clojure.java.io :as io])
  (:import (java.security KeyStore KeyPairGenerator KeyPair)
           (org.bouncycastle.asn1.x500 X500Name)
           (org.bouncycastle.cert.jcajce JcaX509v3CertificateBuilder JcaX509CertificateConverter)
           (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util Date)
           (org.bouncycastle.asn1.x509 Extension BasicConstraints GeneralNames GeneralName)
           (org.bouncycastle.operator.jcajce JcaContentSignerBuilder)
           (java.security.cert Certificate X509Certificate)))

(def default-root-store "root-certs.pfx")
(def default-password "")
(def root-name "do-not-trust-bastel-proxy-root")
(def issuer-name "do-not-trust-bastel-proxy-issuer")

(def key-generator (KeyPairGenerator/getInstance "RSA"))

(defn load-cert-store
  "Load the Java KeyStore from a file. The key store sahould be in the PKCS12 format"
  [key-store-file password]
  (let [key-store (KeyStore/getInstance "PKCS12")
        pwd (.toCharArray password)]
    (with-open [in (io/input-stream key-store-file)]
      (.load key-store in pwd))
    key-store))

(defn- cert-builder [^X500Name subject ^KeyPair key ^X500Name issuer]
  (let [serial-number (BigInteger/valueOf (System/currentTimeMillis))
        valid-from (Instant/now)
        valid-until (.plus valid-from (* 10 360) ChronoUnit/DAYS)
        ^X500Name issuer-name (or issuer subject)]
    (JcaX509v3CertificateBuilder. issuer-name serial-number
                                  (Date/from valid-from) (Date/from valid-until)
                                  subject (.getPublic key))))

(defn build-cert
  "Create a new certificate.
  Name: Certificate name in X500Name notation. For domains should be cn=domain
  issuer: Issuer certificate. nil if it's a root certificate
  cert-authority?: Can this certificate be used to sign sub certificates
  domain: Add the domain to the subject alternative name, required by most browsers"
  ([^String name issuer cert-authority? ^String domain]
   (let [subject (X500Name. name)
         key-pair (.generateKeyPair key-generator)
         cert-builder (cert-builder subject key-pair (::name issuer))
         signer-key (or (::private-key issuer) (.getPrivate key-pair))
         signer (-> (JcaContentSignerBuilder. "SHA256WithRSA") (.build signer-key))]
     (.addExtension cert-builder Extension/basicConstraints true (BasicConstraints. (boolean cert-authority?)))
     (when domain
       (.addExtension cert-builder Extension/subjectAlternativeName true
                      (GeneralNames. (GeneralName. GeneralName/dNSName domain))))
     {::name        subject
      ::private-key (.getPrivate key-pair)
      ::issuer      issuer
      ::cert        (.getCertificate (JcaX509CertificateConverter.) (.build cert-builder signer))}
     ))
  ([name]
   (build-cert name nil true nil)))

(defn- add-to-store [store alias certs password]
  (.setKeyEntry store alias
                (-> certs first ::private-key)
                (.toCharArray password)
                (into-array Certificate (map ::cert certs))))

(defn empty-store []
  (doto (KeyStore/getInstance "PKCS12")
    (.load nil nil)))

(defn store-to-file [store file password]
  (with-open [out (io/output-stream file)]
    (.store store out (.toCharArray password))))

(defn load-or-create-root
  "Loads or creates a root certificate and stores it in the specified file. Returns the key store"
  ([key-store-file password]
   (if (.exists (io/file key-store-file))
     (do
       (load-cert-store key-store-file password))
     (let [store (empty-store)
           root (build-cert (str "cn=" root-name))
           middle (build-cert (str "cn=" issuer-name) root true nil)]
       (add-to-store store root-name [root] password)
       (add-to-store store issuer-name [middle root] password)
       (store-to-file store key-store-file password)
       store)))
  ([]
   (load-or-create-root default-root-store default-password)))

(defn store-cert [store name password]
  "Load the certificate from the store, including it's ::private-key, ::issuer etc"
  (let [^X509Certificate cert (.getCertificate store name)
        ^String cert-name (.. cert getSubjectDN getName)
        ^String issuer-name (.. cert getIssuerDN getName)
        issuer-plain-name (subs issuer-name 3)]
    {::cert cert
     ::private-key (.getKey store name (.toCharArray password))
     ::name (X500Name. cert-name)
     ::issuer (if (= cert-name issuer-name)
                nil
                (store-cert store issuer-plain-name password))}))

(defn- issuer [store password]
  (store-cert store issuer-name password))

(defn new-domain-cert
  "Creates a new certificate for the domain given domain.
  When the issuer certificate is not passed the default root certificate is used"
  ([issuer domain]
   (build-cert (str "cn=" domain) issuer false domain))
  ([domain]
   (let [store (load-or-create-root)
         iss (issuer store default-password)]
     (build-cert (str "cn=" domain) iss false domain))))

(defn cert-chain
  "Returns the chain of the provided certificate"
  ([cert chain]
   (let [current cert
         parent (::issuer cert)
         updated (conj chain current)]
     (if parent
       (cert-chain parent updated)
       (conj updated)
       )))
  ([cert] (cert-chain cert [])))

(defn add-domains-cert
  "Create and add certificate to the specified store"
  [store domains root-store-file password]
  (let [root-store (load-or-create-root root-store-file password)]
    (doseq [domain domains]
      (let [cert (new-domain-cert (issuer root-store password) domain)
            chain (cert-chain cert)]
        (add-to-store store domain chain password)))))

(defn create-domains-store
  "Create certs for the provided domains and store it in the specified file in PKCS12 format"
  ([store-file domains password]
   (let [store (empty-store)]
     (add-domains-cert store domains default-root-store password)
     (store-to-file store store-file password)))
  ([store-file domains]
   (create-domains-store store-file domains default-password)))

(comment
  (io/delete-file default-root-store)
  (create-domains-store "my-certs.pfx" ["local.gamlor.info" "more.local.gamlor.info"]))