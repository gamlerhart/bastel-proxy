#!/usr/bin/env bash

WORKING_DIR=$(dirname "$0")
cd ${WORKING_DIR}

java -Xmx64m -jar ./bastel-proxy-0.2.jar $*