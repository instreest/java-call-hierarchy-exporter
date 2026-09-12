#!/usr/bin/env bash
# プラグインのクライアント層（eclipse-plugin/src-ui/jche/eclipse/server）の検査。
#
#   bash test/plugin-client/run.sh
#
# 画面（SWT）は Eclipse が無いと動かせないが、その下の層――子プロセスを起動し、
# プロトコルで話し、返ってきた行を木に組み直すところ――は Eclipse 無しで動かせる。
# ここが壊れると画面には何も出ないので、自動で守る値打ちがある。
#
# 併せて「クライアント層が Java 8 でコンパイルできること」も確認する（--release 8）。
# プラグインを Java 8 の Eclipse でも動かす計画のため（docs/out-of-process-analysis-design.md）。
#
# 解析に使う lib/（jche-core.jar と jdt/*.jar）は eclipse-plugin をビルドすると作られる。
# 既にあればそれを使い、無ければ mvn で作る。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

LIB=$ROOT/eclipse-plugin/target/classes/lib
if [ ! -f "$LIB/jche-core.jar" ]; then
    echo "== lib/ が無いのでビルドする（mvn -f eclipse-plugin/pom.xml package）"
    (cd "$ROOT/eclipse-plugin" && mvn -B -q --no-transfer-progress package) || { echo "FAIL"; exit 1; }
fi
if [ ! -f "$LIB/jche-core.jar" ] || [ -z "$(ls "$LIB/jdt"/*.jar 2>/dev/null)" ]; then
    echo "NG   同梱する lib/ が作られていない"; echo "FAIL"; exit 1
fi

echo "== クライアント層を Java 8 でコンパイルする"
javac --release 8 -nowarn -d "$WORK/client" -encoding UTF-8 \
    "$ROOT"/eclipse-plugin/src-ui/jche/eclipse/server/*.java 2>"$WORK/javac.log"
if [ $? -ne 0 ]; then
    echo "NG   Java 8 でコンパイルできない"; sed 's/^/       /' "$WORK/javac.log"; echo "FAIL"; exit 1
fi
echo "  OK   Java 8 でコンパイルできる"
javac --release 8 -nowarn -cp "$WORK/client" -d "$WORK/probe" -encoding UTF-8 ClientProbe.java 2>&1 \
    | grep -v bootstrap
[ -f "$WORK/probe/ClientProbe.class" ] || { echo "NG   検査プログラムをコンパイルできない"; echo "FAIL"; exit 1; }

CP="$(ls "$LIB"/jdt/*.jar | paste -sd:):$LIB/jche-core.jar"
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/java}
JAVA=${JAVA:-$(command -v java)}
echo "== 子プロセスを起動して一連のやりとりを確認する（java=$JAVA）"
OUT=$(java -cp "$WORK/client:$WORK/probe" ClientProbe "$JAVA" "$CP" "$WORK" \
    "$ROOT/test/regression/whole/config.properties" 'fx.dao.UserDaoImpl#<init>()' 2>&1)
echo "$OUT" | sed 's/^/  /'

if grep -q '^NG' <<<"$OUT" || ! grep -q '^DONE' <<<"$OUT"; then
    echo "FAIL"; exit 1
fi
echo "PASS"
