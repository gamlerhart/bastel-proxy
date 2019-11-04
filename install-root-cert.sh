#!/usr/bin/env bash
# Hacky script to install the CA into the different trust stores.
# Unfortunalty different distros install the certificates in different places.
# Script tries to just plow through and hope for the best.
# Tested on:
#   - Ubuntu 18.04

echo "Import CA (do-not-trust-bastel-proxy-root) to distro's trust store"

WORKING_DIR=$(dirname "$0")
cd ${WORKING_DIR}

# use explicit passed in home directory when available
if [[ $1 != "" ]]; then
    export HOME=$1
fi

# Extract the certificate from the keystore
openssl pkcs12 -passin 'pass:' -in root-certs.pfx -nokeys -cacerts -out root-cert.crt

which certutil 2>&1
if [[ $? -ne 0 ]]; then
    echo "Installing certutil to import CA into Browsers trust store"
    apt install -y libnss3-tools
    which certutil
    if [[ $? -ne 0 ]]; then
        >&2 echo "certutil is not installed. Cannot import certificates. Please install certutil"
        exit -1
    fi
fi

CERT_NAME="DO NOT TRUST Bastel Proxy"
# Reference used on how to install the certificates in different places
# Arch Linux, Fedora
which trust 2>&1
TRUST_EXISTS=$?
# Debian, Ubuntu
which update-ca-certificates 2>&1
UPDATE_CA_EXISTS=$?
if [[ $TRUST_EXISTS -eq 0 ]]; then
    trust anchor --store root-cert.crt
    if [[ $? -ne 0 ]];then
        # Arch path
        [[ -d /etc/ca-certificates/trust-source ]] && cp root-cert.crt /etc/ca-certificates/trust-source/do-not-trust-bastel-proxy-root.p11-kit
        # Fedora path
        [[ -d /etc/pki/ca-trust/source ]] && cp root-cert.crt /etc/pki/ca-trust/source/do-not-trust-bastel-proxy-root.p11-kit
    fi
    update-ca-trust
fi
if [[ $UPDATE_CA_EXISTS -eq 0 ]]; then
    mkdir -p /usr/local/share/ca-certificates/extra
    cp root-cert.crt /usr/local/share/ca-certificates/do-not-trust-bastel-proxy-root.crt
    update-ca-certificates
fi
if [[ $TRUST_EXISTS -ne 0 ]] && [[ $UPDATE_CA_EXISTS -ne 0 ]]; then
    echo "Couldn't install certificates in this Linux distribution. Please install the root-cert.crt manually"
fi

echo "Import CA (do-not-trust-bastel-proxy-root) to Firefox and Chromium"
for CERT_DB in $(find $HOME/.mozilla* $HOME/.pki $HOME/snap/chromium -name "cert9.db" 2>/dev/null)
do
  CERT_DIR=$(dirname ${CERT_DB});
  certutil -A -n "${CERT_NAME}" -t "TCu,Cuw,Tuw" -i root-cert.crt -d sql:${CERT_DIR}
  echo "CA imported to $CERT_DIR"
done


echo "Import CA (do-not-trust-bastel-proxy-root) to the JDK"
if [[ -f $JAVA_HOME/bin/keytool ]]; then
    JAVA_KEYTOOL="$JAVA_HOME/bin/keytool"
    if [[ -f "$JAVA_HOME/jre/lib/security/cacerts" ]]; then
        $JAVA_KEYTOOL -importcert -alias do-not-trust-bastel-proxy-root -keystore "$JAVA_HOME/jre/lib/security/cacerts" -storepass changeit -file root-cert.crt
    elif [[ -f "$JAVA_HOME/lib/security/cacerts" ]]; then
        $JAVA_KEYTOOL -importcert -alias do-not-trust-bastel-proxy-root -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit -file root-cert.crt
    else
        echo "Could not find Java cert store. Did not import into JDK root store. \$JAVA_HOME not set"
    fi
fi
