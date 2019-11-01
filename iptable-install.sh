#!/usr/bin/env bash
# Create the IP tables redirecting the high level ports to high level ones
HTTP_SRC=$1
HTTP_DEST=$2
HTTPS_SRC=$3
HTTPS_DEST=$4
iptables -t nat --list OUTPUT --numeric | grep "dpt:$HTTP_SRC redir ports $HTTP_DEST" > /dev/null || \
    iptables -t nat -I OUTPUT -p tcp -d 127.0.0.1 --dport $HTTP_SRC -j REDIRECT --to-ports $HTTP_DEST
iptables -t nat --list OUTPUT --numeric | grep "dpt:$HTTPS_SRC redir ports $HTTPS_DEST" > /dev/null || \
    iptables -t nat -I OUTPUT -p tcp -d 127.0.0.1 --dport $HTTPS_SRC -j REDIRECT --to-ports $HTTPS_DEST
echo "Installed IP tables" | tee ./ip-tables.log
echo "To uninstall in case of an error:" | tee ./ip-tables.log
echo iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport $HTTP_SRC -j REDIRECT --to-ports $HTTP_DEST | tee ./ip-tables.log
echo iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport $HTTPS_SRC -j REDIRECT --to-ports $HTTPS_DEST | tee ./ip-tables.log