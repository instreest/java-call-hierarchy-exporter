#!/usr/bin/env bash
# pom.xml と JBang の //DEPS 行の食い違いを検出する。
#
#   bash test/pom/run.sh
#
# 依存の定義は 2 か所にある。
#   src/CallHierarchyExporter.java の //DEPS 行 … jbang で実行するときの本流
#   pom.xml                                   … Eclipse（Pleiades）の m2e が依存を解決するためだけのもの
# JDT の版を上げるときに片方だけ書き換えると、jbang と Eclipse で違う版のライブラリを見ることになり、
# Eclipse 上では通るのに jbang では通らない（またはその逆）といった食い違いが黙って起きる。
# ここでは両方から group:artifact:version を取り出して、集合として一致することだけを見る。
#
# ファイルの中身を読むだけなので、JDK も Maven もネットワークも要らない。
# 言語レベル（pom.xml の maven.compiler.release）は //JAVA 行とは意味が違うので比べない
# （docs/eclipse-maven-qa.md の Q1・Q2）。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)

# //DEPS 行は 1 行に空白区切りで複数書ける。行末コメントは無い前提
deps=$(grep -E '^//DEPS ' "$ROOT/src/CallHierarchyExporter.java" | sed -E 's|^//DEPS ||' | tr ' ' '\n' | grep -v '^$' | sort)

# pom.xml の <dependency> ブロックを 1 件ずつ group:artifact:version に組み立てる。
# 子要素の順番に依存しないよう、ブロック内で個別に取り出す
pom=$(awk '
    /<dependency>/  { g=a=v=""; inblock=1; next }
    /<\/dependency>/ { if (inblock) print g ":" a ":" v; inblock=0; next }
    inblock && /<groupId>/    { sub(/.*<groupId>/, "");    sub(/<\/groupId>.*/, "");    g=$0 }
    inblock && /<artifactId>/ { sub(/.*<artifactId>/, ""); sub(/<\/artifactId>.*/, ""); a=$0 }
    inblock && /<version>/    { sub(/.*<version>/, "");    sub(/<\/version>.*/, "");    v=$0 }
' "$ROOT/pom.xml" | sort)

echo "== //DEPS =="; echo "$deps" | sed 's/^/  /'
echo "== pom.xml =="; echo "$pom"  | sed 's/^/  /'

if [ -z "$deps" ] || [ -z "$pom" ]; then
    echo "NG   どちらかから依存が取り出せない"; echo "FAIL"; exit 1
fi
if [ "$deps" = "$pom" ]; then
    echo "  OK   //DEPS と pom.xml の依存が一致する"
    echo "PASS"
else
    echo "  NG   //DEPS と pom.xml の依存が食い違っている。両方を同じ版に揃えること"
    diff <(echo "$deps") <(echo "$pom") | sed 's/^/       /'
    echo "FAIL"; exit 1
fi
