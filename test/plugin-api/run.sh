#!/usr/bin/env bash
# 「プラグインが下限の Eclipse でも動く形か」の検査。
#
#   bash test/plugin-api/run.sh
#
# 本番のビルド（eclipse-plugin/pom.xml）は新しい Eclipse の jar でコンパイルするので、
# 「新しい Eclipse にしか無い API」を使っても気づけない。ここでは
# Eclipse 4.17（2020-09）の jar だけをクラスパスにして、
# プラグインのソースを --release 11 でコンパイルしてみる。
#
# 見ているのは2つ。
#   1) 使っている API が、下限の Eclipse にもあること
#      （実際この検査で PlatformUI.getDialogSettingsProvider（4.24〜）の混入が見つかった）
#   2) 文法が Java 11 に収まっていること（MANIFEST.MF の BREE と揃える）
#
# 下限を 4.6 / Java 8 から 4.17 / Java 11 へ上げた経緯は docs/eclipse-plugin-java-floor-qa.md
#
# 依存の版は test/plugin-api/pom.xml に固定してある。Maven と JDK（9 以上。--release のため）が要る。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== 下限の Eclipse（4.17）の jar を集める"
if ! mvn -B -q --no-transfer-progress dependency:build-classpath \
        -Dmdep.outputFile="$WORK/cp.txt" 2>"$WORK/mvn.log"; then
    echo "NG   依存を解決できませんでした"; sed 's/^/       /' "$WORK/mvn.log" | tail -20
    echo "FAIL"; exit 1
fi
CP=$(cat "$WORK/cp.txt")
echo "  OK   $(tr ':' '\n' <<<"$CP" | wc -l) 個の jar（jdt.core 3.23.0 / ui.workbench 3.120.0 ほか）"

echo "== プラグインを --release 11 でコンパイルする"
if javac --release 11 -nowarn -cp "$CP" -d "$WORK/classes" -encoding UTF-8 \
        $(find "$ROOT/eclipse-plugin/src-ui" -name '*.java') 2>"$WORK/javac.log"; then
    echo "  OK   下限の Eclipse の API と Java 11 の文法だけで書けている"
else
    echo "  NG   下限の Eclipse ではコンパイルできない"
    grep -E "error:|エラー" "$WORK/javac.log" | sed 's/^/       /' | head -20
    echo "FAIL"; exit 1
fi

echo "== クラスファイルの版を確かめる"
# Java 11 のクラスファイルはメジャー版 55。ここが上がっていると、下限の JVM で読めない
bad=0
while read -r class; do
    major=$(od -An -t u1 -j 7 -N 1 "$class" | tr -d ' ')
    if [ "$major" != "55" ]; then
        echo "  NG   $(basename "$class") のメジャー版が $major（55 であるべき）"
        bad=$((bad + 1))
    fi
done < <(find "$WORK/classes" -name '*.class' | head -50)
[ "$bad" -eq 0 ] && echo "  OK   すべて Java 11（メジャー版 55）のクラスファイル"

[ "$bad" -eq 0 ] && echo "PASS" || { echo "FAIL"; exit 1; }
