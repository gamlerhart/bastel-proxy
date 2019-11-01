#!/usr/bin/env bash
echo "Uninstalling IP tables" | tee ./ip-tables.log
# Create the IP tables redirecting the high level ports to high level ones
HTTP_SRC=$1
HTTP_DEST=$2
HTTPS_SRC=$3
HTTPS_DEST=$4
iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport $HTTP_SRC -j REDIRECT --to-ports $HTTP_DEST
iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport $HTTPS_SRC -j REDIRECT --to-ports $HTTPS_DEST
echo "Uninstalled IP tables" | tee ./ip-tables.log