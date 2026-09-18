#!/usr/bin/env bash
# VSCode プラグイン（vscode-plugin/）のうち、`vscode` モジュールに触らない層の検査。
#
#   bash test/vscode/run.sh
#   JCHE_CP="<classpath>" bash test/vscode/run.sh     # 既にコンパイル済みのクラスを使う
#
# 画面は VSCode 本体が無いと動かせないが、その下の層――子プロセスを起動し、プロトコルで話し、
# 返ってきた行を木に組み直す・設定ファイルを用意する――は Node だけで動かせる。
# ここが壊れると画面には何も出ないので、自動で守る値打ちがある（Eclipse 版の
# test/plugin-client/run.sh と対になる。JDK の選び方・取得先 URL は同じ期待値で検査する）。
#
# VSCode 本体を落としてくる検査（@vscode/test-electron）は、CI の時間と閉域環境を考えて採らない
# （docs/vscode-plugin-design.md §10）。
#
# 要るもの: Node 22 以上と npm。JCHE_CP が無ければ jbang に依存を解決させてコンパイルする。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
PLUGIN=$ROOT/vscode-plugin
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if ! command -v node >/dev/null || ! command -v npm >/dev/null; then
    echo "NG   node / npm が要ります"; echo "FAIL"; exit 1
fi

if [ -z "${JCHE_CP:-}" ]; then
    echo "== classpath を用意する（jbang）"
    CP=$(bash "$ROOT/jbangw/jbang" info classpath "$ROOT/src/jche/CallHierarchyExporter.java" \
        | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)
    if [ -z "$CP" ]; then
        echo "NG   依存を解決できませんでした"; echo "FAIL"; exit 1
    fi
    javac --release 17 -nowarn -cp "$CP" -d "$WORK/classes" -encoding UTF-8 \
        $(find "$ROOT/src" -name '*.java') || { echo "FAIL"; exit 1; }
    JCHE_CP="$CP:$WORK/classes"
fi

JAVA=${JAVA_HOME:+$JAVA_HOME/bin/java}
JAVA=${JAVA:-$(command -v java)}

cd "$PLUGIN"
if [ ! -d node_modules ]; then
    echo "== 依存を入れる（npm ci）"
    npm ci --no-audit --no-fund --loglevel=error || { echo "NG   npm ci に失敗"; echo "FAIL"; exit 1; }
fi

echo "== 型検査・束ね・検査（java=$JAVA）"
# test/demo の中で呼び出し元が多いメソッドと、AT の検査に使うファイル:行。
# 存在しなくなったらこの検査も直すこと（test/server/run.sh と同じ値）
JCHE_JAVA="$JAVA" JCHE_CP="$JCHE_CP" \
JCHE_CONFIG="$ROOT/test/regression/whole/config.properties" \
JCHE_TARGET='fx.dao.UserDaoImpl#<init>()' \
JCHE_AT='src/fx/dao/DaoFactory.java:7' \
    npm test --silent 2>&1 | grep -vE '^\s*$' | sed 's/^/  /'
STATUS=${PIPESTATUS[0]}

echo "== 束ねた検査に vscode の require が混ざっていないこと"
# 検査は「vscode に触らない層」だけを対象にしている。混ざっていたら層の切り分けが崩れている
if grep -l "require(\"vscode\")" out/test/*.js 2>/dev/null; then
    echo "  NG   検査が vscode モジュールに依存している"; STATUS=1
else
    echo "  OK   検査は vscode モジュールに依存していない"
fi

echo "== 拡張本体を束ねられること（dist/extension.js）"
if npm run build --silent >/dev/null 2>&1 && [ -s dist/extension.js ]; then
    echo "  OK   dist/extension.js"
else
    echo "  NG   拡張本体を束ねられない"; STATUS=1
fi

if [ "$STATUS" -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
