#!/bin/sh
# ---------------------------------------------------------------
# 実行例:
#   ./run.sh config/config.properties
#
# ヒープが足りない場合は JAVA_OPTS で調整してください。
#   JAVA_OPTS="-Xmx4g" ./run.sh config/config.properties
# ---------------------------------------------------------------
DIR=$(cd "$(dirname "$0")" && pwd)
if [ -z "$1" ]; then
  echo "使い方: $0 <config.propertiesのパス>"
  exit 1
fi
exec java ${JAVA_OPTS:--Xmx2g} \
  -Dfile.encoding=UTF-8 \
  -cp "$DIR/*:$DIR/lib/*" \
  jp.co.example.callhierarchy.CallHierarchyExporter "$1"
