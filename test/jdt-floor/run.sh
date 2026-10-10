#!/usr/bin/env bash
# 「本体が JDT の下限（3.28.0。Eclipse 2021-12）でもコンパイルでき、今の JDT と同じ結果を出すか」の検査。
#
#   bash test/jdt-floor/run.sh
#
# 本体はそこにある JDT の jar に対してソースからコンパイルされる（jbang の //DEPS も、閉域ネットワークの手順で
# Pleiades / Eclipse の jar を集めて javac するときも）。ほかの検査は //DEPS の版（新しい JDT）でコンパイルするので、
# 「新しい JDT にしか無い API」を直接書いても気づけない。下限より新しい API は jche.analysis.JdtCompat を通して
# 名前で引く決まりで、それが守られていることをここだけが見る。
#
# 見ているのは 2 つ。
#   1) 下限の jar（test/jdt-floor/pom.xml。Eclipse 2021-12 の版に固定）だけで本体がコンパイルできること
#   2) その本体を下限の JDT で動かしても、test/regression の期待値（今の JDT で作ったもの）と一致すること。
#      test/regression の題材は Java 17 までの文法で書いてあり（record は使うが、switch のパターンなどは使わない）、
#      下限の JDT でも全部を読める。新しい文法の題材を test/regression に足すと、ここが落ちる。そのときは
#      題材を ctorbody / jls のような JDK 25・最新の JDT 前提の検査の側に置く
#
# 下限を 3.28.0 にした理由と、下げると増えるもの・読めなくなる文法は docs/jdt-floor-qa.md。
#
# Maven と JDK 25（test/regression の期待値は JDK 25 で作る。JDT は実行 JVM の標準ライブラリを解析に使う）が要る。
# JCHE_JAVA / JCHE_JAVAC で差し替えられる（既定は PATH の java / javac）。
# 本体のクラスは build/jdt-floor/classes にできる（コミットしない）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールでログの文言が変わる）
export JCHE_LANG=en
ROOT=$(cd ../.. && pwd)
JAVA_BIN=${JCHE_JAVA:-java}
JAVAC_BIN=${JCHE_JAVAC:-javac}
FLOOR=3.28.0
CLASSES=$ROOT/build/jdt-floor/classes
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== 下限の JDT（$FLOOR）の jar を集める"
if ! mvn -B -q --no-transfer-progress dependency:build-classpath \
        -Dmdep.outputFile="$WORK/cp.txt" 2>"$WORK/mvn.log"; then
    echo "  NG   依存を解決できませんでした"; sed 's/^/       /' "$WORK/mvn.log" | tail -20
    echo "FAIL"; exit 1
fi
CP=$(cat "$WORK/cp.txt")
if ! tr ':' '\n' <<<"$CP" | grep -q "/org.eclipse.jdt.core-$FLOOR.jar$"; then
    echo "  NG   classpath に org.eclipse.jdt.core-$FLOOR.jar がありません（pom.xml の版と FLOOR を揃える）"
    echo "FAIL"; exit 1
fi
echo "  OK   $(tr ':' '\n' <<<"$CP" | wc -l) 個の jar（jdt.core $FLOOR ほか。Eclipse 2021-12）"

echo "== 本体を下限の JDT でコンパイルする"
# 警告は見ない（lint は regression ジョブが //DEPS の版で見る）。見るのは API があるかだけ
rm -rf "$CLASSES"
if "$JAVAC_BIN" --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d "$CLASSES" \
        $(find "$ROOT/src" -name '*.java') 2>"$WORK/javac.log"; then
    echo "  OK   下限の JDT の API だけで書けている"
else
    echo "  NG   下限の JDT ではコンパイルできない（下限より新しい API は jche.analysis.JdtCompat を通す）"
    grep -E -A2 "error:" "$WORK/javac.log" | sed 's/^/       /' | head -30
    echo "FAIL"; exit 1
fi

echo "== 下限の JDT で回帰テストを回す（期待値は今の JDT と同じ）"
JCHE_CMD="$JAVA_BIN -cp $CLASSES:$CP jche.CallHierarchyExporter" bash ../regression/run.sh > "$WORK/regression.log" 2>&1
status=$?
# 下限の JDT で動いたこと（解析できる Java の上限が 17）をログで確かめる。違う JDT が混ざっていたら意味が無い
if grep -q "highest this JDT supports: 17$" ../regression/whole/run-1.log; then
    echo "  OK   JDT $FLOOR で解析した（解析できる Java の上限 17）"
else
    echo "  NG   test/regression/whole/run-1.log に「highest this JDT supports: 17」がありません（別の JDT で動いた）"
    status=1
fi
if [ "$status" -eq 0 ]; then
    echo "  OK   test/regression が通った（$(grep -c '^ *OK' "$WORK/regression.log") 項目）"
    echo "PASS"
else
    echo "  NG   test/regression が下限の JDT で通らない"
    grep -E "^ *(NG|DIFF)|^[<>]" "$WORK/regression.log" | sed 's/^/       /' | head -40
    echo "FAIL"; exit 1
fi
