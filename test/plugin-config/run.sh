#!/usr/bin/env bash
# 自動生成した設定が、解析側と同じ読み方でそのまま読み戻せることの検査。
#
#   bash test/plugin-config/run.sh
#
# Eclipse プラグインは、設定ファイルが無いときプロジェクトの構成から設定を組み立て、
# 一時ファイルに書いて子プロセスへ渡す（EclipseProjectConfig#toFileText → Config）。
# 値には Windows のパスがそのまま入るが、properties ではバックスラッシュがエスケープなので、
# 逃がさずに書くと「バックスラッシュ + u」が Unicode エスケープと解釈されて
# 解析ごと失敗する（Malformed uxxxx encoding）。逃がし忘れを往復で検出する。
#
# 依存（Eclipse の jar）は test/plugin-api/pom.xml のものを使う。Maven と JDK（9 以上）が要る。
set -uo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== Eclipse の jar を集める"
if ! mvn -B -q --no-transfer-progress -f ../plugin-api/pom.xml dependency:build-classpath \
        -Dmdep.outputFile="$WORK/cp.txt" 2>"$WORK/mvn.log"; then
    echo "NG   依存を解決できませんでした"; sed 's/^/       /' "$WORK/mvn.log" | tail -20
    echo "FAIL"; exit 1
fi
CP=$(cat "$WORK/cp.txt")

echo "== プラグインと検査プログラムをコンパイルする"
if ! javac --release 8 -nowarn -cp "$CP" -d "$WORK/classes" -encoding UTF-8 \
        $(find "$ROOT/eclipse-plugin/src-ui" -name '*.java') ConfigTextProbe.java 2>"$WORK/javac.log"; then
    echo "NG   コンパイルできない"; sed 's/^/       /' "$WORK/javac.log" | head -20
    echo "FAIL"; exit 1
fi

echo "== 書き出した設定を読み戻す"
OUT=$(java -cp "$WORK/classes:$CP" jche.eclipse.ConfigTextProbe 2>&1)
echo "$OUT" | sed 's/^/  /'
if grep -q '^NG' <<<"$OUT" || ! grep -q '^DONE' <<<"$OUT"; then
    echo "FAIL"; exit 1
fi
echo "PASS"
