#!/usr/bin/env bash
# 「プラグインが古い Eclipse でも動く形か」の検査。
#
#   bash test/plugin-api/run.sh
#
# 本番のビルド（eclipse-plugin/pom.xml）は新しい Eclipse の jar でコンパイルするので、
# 「新しい Eclipse にしか無い API」を使っても気づけない。ここでは
# Eclipse 4.6（Neon、2016年）相当の古い jar だけをクラスパスにして、
# プラグインのソースを --release 8 でコンパイルしてみる。
#
# 見ているのは2つ。
#   1) 使っている API が、古い Eclipse にもあること
#      （実際この検査で PlatformUI.getDialogSettingsProvider（4.24〜）の混入が見つかった）
#   2) 文法が Java 8 に収まっていること（Java 8 で動く Eclipse に入れるため）
#
# 依存の版は test/plugin-api/pom.xml に固定してある。Maven と JDK（9 以上。--release のため）が要る。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== 古い Eclipse（4.6 相当）の jar を集める"
if ! mvn -B -q --no-transfer-progress dependency:build-classpath \
        -Dmdep.outputFile="$WORK/cp.txt" 2>"$WORK/mvn.log"; then
    echo "NG   依存を解決できませんでした"; sed 's/^/       /' "$WORK/mvn.log" | tail -20
    echo "FAIL"; exit 1
fi
CP=$(cat "$WORK/cp.txt")
echo "  OK   $(tr ':' '\n' <<<"$CP" | wc -l) 個の jar（jdt.core 3.10.0 / ui.workbench 3.108.2 ほか）"

echo "== プラグインを --release 8 でコンパイルする"
if javac --release 8 -nowarn -cp "$CP" -d "$WORK/classes" -encoding UTF-8 \
        $(find "$ROOT/eclipse-plugin/src-ui" -name '*.java') 2>"$WORK/javac.log"; then
    echo "  OK   古い Eclipse の API と Java 8 の文法だけで書けている"
else
    echo "  NG   古い Eclipse ではコンパイルできない"
    grep -E "error:|エラー" "$WORK/javac.log" | sed 's/^/       /' | head -20
    echo "FAIL"; exit 1
fi

echo "== クラスファイルの版を確かめる"
# Java 8 のクラスファイルはメジャー版 52。ここが上がっていると古い JVM で読めない
bad=0
while read -r class; do
    major=$(od -An -t u1 -j 7 -N 1 "$class" | tr -d ' ')
    if [ "$major" != "52" ]; then
        echo "  NG   $(basename "$class") のメジャー版が $major（52 であるべき）"
        bad=$((bad + 1))
    fi
done < <(find "$WORK/classes" -name '*.class' | head -50)
[ "$bad" -eq 0 ] && echo "  OK   すべて Java 8（メジャー版 52）のクラスファイル"

[ "$bad" -eq 0 ] && echo "PASS" || { echo "FAIL"; exit 1; }
