#!/usr/bin/env bash
# 確認してほしいことの案内（出力フォルダの warnings.txt）の検査。
#
#   bash test/warnings/run.sh
#
# warnings.txt は「想定どおりの状態（ビルドが通り、依存 jar がすべて解決できている）で動かなかった」
# 実行でだけ作る（docs/output-files-simplify-qa.md の Q3〜）。ここでは使い捨てのプロジェクトを作って、
#   - 正常な状態では作らないこと
#   - 典型の状態（依存 jar が無い・ローカルリポジトリが無い・設定の指定先が無い・コンパイルエラー・
#     実行の失敗）で作り、該当の項目と明細が載ること
#   - どの実行でも「warnings.txt がある」⇔「run.log に [WARN] / [ERROR] の行がある」こと
#   - コンパイルエラー・構文エラーのファイルの一覧（上限まで）が、差分更新でも全件解析と同じこと
#   - 表示言語を日本語にすると日本語で書かれること
# を確かめる。
#
# ツール本体は javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （test/ctorbody/run.sh と同じ経路）。依存 jar は test/localrepo から集める（ネットワークは使わない）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールで照合が変わる）
export JCHE_LANG=en
export LANG=C.UTF-8
ROOT=$(cd ../.. && pwd)
JBANG="bash $ROOT/jbangw/jbang"
fail=0

ok()   { echo "  OK   $1"; }
ng()   { echo "  NG   $1"; fail=1; }

CP=${JCHE_CP:-$($JBANG info classpath "$ROOT/src/jche/CallHierarchyExporter.java" \
    | tr ':' '\n' | grep -v '/cache/jars/' | paste -sd:)}
JAVA_BIN=${JCHE_JAVA:-"$($JBANG jdk home 25)/bin/java"}
JAVAC_BIN=${JCHE_JAVAC:-"$($JBANG jdk home 25)/bin/javac"}
if [ -z "$CP" ]; then
    echo "  NG   jbang から JDT の classpath を取得できませんでした"; echo "FAIL"; exit 1
fi

rm -rf build work
if [ -z "${JCHE_CLASSES:-}" ]; then
    "$JAVAC_BIN" --release 17 -Xlint:all -Werror -Xdoclint:all,-missing -encoding UTF-8 \
        -cp "$CP" -d build $(find "$ROOT/src" -name '*.java') \
        || { echo "  NG   コンパイルに失敗しました"; echo "FAIL"; exit 1; }
    CLASSES=$PWD/build
else
    CLASSES=$JCHE_CLASSES
fi

# --- 使い捨てのプロジェクト ---
# test/maven-demo を複製し、pom.xml と設定ファイルをケースごとに書き換える。
# 依存 jar は test/localrepo から集まる（正常な状態では警告が 1 つも出ない）
make_project() {   # $1=フォルダ名  $2=設定ファイルに足す行（改行区切り）
    mkdir -p "work/$1"
    cp -R "$ROOT/test/maven-demo/src" "$ROOT/test/maven-demo/pom.xml" "work/$1/"
    cat > "work/$1/config.properties" <<EOF
project.root=.
source.folders=src/main/java
library.repositories=$ROOT/test/localrepo
source.encoding=UTF-8
output.folder=./out
cache.folder=./.cache
$2
EOF
}

analyze() {   # $1=フォルダ名 -> 出力フォルダを OUT に、終了コードを STATUS に入れる
    ( cd "work/$1" && "$JAVA_BIN" -cp "$CLASSES:$CP" \
        jche.CallHierarchyExporter config.properties ) > "work/$1.console.log" 2>&1
    STATUS=$?
    OUT=$(ls -d "work/$1"/out/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##')
    check_no_temp_files "$1"
}

# 実行のあとに、キャッシュのフォルダに一時ファイル（依存の索引・エッジの記録・型解決に失敗した呼び出しの行。
# jche.cache.TempFiles）が残らないこと。型解決に失敗した呼び出しの行は、そういう呼び出しがある実行でだけ作るので、
# ここ（依存 jar の不足・コンパイルエラーのケースがある）で見る。キャッシュ本体の一時ファイル
# （analysis-cache.tsv.tmp）は中断からの引き継ぎに使うので、失敗した実行では残ってよい
check_no_temp_files() {   # $1=フォルダ名
    local left
    left=$(find "work/$1/.cache" -name '*.tmp' ! -name 'analysis-cache.tsv.tmp' 2>/dev/null)
    if [ -z "$left" ]; then
        ok "$1: キャッシュのフォルダに一時ファイルが残らない"
    else
        ng "$1: キャッシュのフォルダに一時ファイルが残っている: $left"
    fi
}

# warnings.txt がある ⇔ run.log に [WARN] / [ERROR] がある
check_invariant() {   # $1=ラベル
    if [ -z "$OUT" ] || [ ! -f "$OUT/run.log" ]; then
        ng "$1: 出力フォルダか run.log がありません（work/$1.console.log）"; return
    fi
    local has_warn=0 has_file=0
    grep -q -E '\[(WARN|ERROR)\]' "$OUT/run.log" && has_warn=1
    [ -f "$OUT/warnings.txt" ] && has_file=1
    if [ "$has_warn" = "$has_file" ]; then
        ok "$1: warnings.txt の有無が run.log の [WARN]/[ERROR] の有無と一致する（$has_file）"
    else
        ng "$1: warnings.txt=$has_file なのに run.log の [WARN]/[ERROR]=$has_warn"
    fi
}

expect_in_warnings() {   # $1=ラベル  $2=含むはずの文字列
    if [ -n "$OUT" ] && grep -q -F -- "$2" "$OUT/warnings.txt" 2>/dev/null; then
        ok "$1: warnings.txt に「$2」がある"
    else
        ng "$1: warnings.txt に「$2」が無い"
    fi
}

# 1. 正常な状態: 作らない
make_project clean ""
analyze clean
check_invariant clean
[ -n "$OUT" ] && [ ! -f "$OUT/warnings.txt" ] && ok "clean: 正常な状態では warnings.txt を作らない" \
    || ng "clean: 正常な状態なのに warnings.txt がある（または出力が無い）"

# 2. 依存 jar がローカルリポジトリに無い
make_project deps ""
sed -i 's#</dependencies>#  <dependency><groupId>sample.deps</groupId><artifactId>absent</artifactId><version>9.9</version></dependency>\n  </dependencies>#' work/deps/pom.xml
analyze deps
check_invariant deps
expect_in_warnings deps "Dependency jars are not resolved"
expect_in_warnings deps "sample.deps:absent:9.9"
grep -q -F "[WARN] Dependency jars:" "$OUT/run.log" 2>/dev/null \
    && ok "deps: run.log に依存 jar の警告がある" || ng "deps: run.log に依存 jar の警告が無い"

# 3. ローカルリポジトリの指定先が無い（書き出しが「Dependency jars:」にそろっていること）
make_project repos "library.repositories=./nowhere"
analyze repos
check_invariant repos
grep -q -F "[WARN] Dependency jars: the folder in library.repositories was not found" "$OUT/run.log" 2>/dev/null \
    && ok "repos: ローカルリポジトリの警告も依存 jar の書き出しで出る" \
    || ng "repos: ローカルリポジトリの警告の書き出しが依存 jar とそろっていない"
expect_in_warnings repos "Dependency jars are not resolved"

# 4. 設定ファイルの指定先が無い
make_project config "source.folders=src/main/java,src/missing"
analyze config
check_invariant config
expect_in_warnings config "A folder or file given in the config file was not found"
expect_in_warnings config "src/missing"

# 5. コンパイルエラー（型が無い）と構文エラー
make_project build ""
cat > work/build/src/main/java/sample/app/Broken.java <<'EOF'
package sample.app;

public class Broken {
    void run() {
        new NoSuchType().go();
    }
}
EOF
cat > work/build/src/main/java/sample/app/Syntax.java <<'EOF'
package sample.app;

public class Syntax {
    void run( {
    }
}
EOF
analyze build
check_invariant build
expect_in_warnings build "The sources have compile errors"
expect_in_warnings build "src/main/java/sample/app/Broken.java"
expect_in_warnings build "src/main/java/sample/app/Syntax.java"
# 2 回目（キャッシュを再利用した実行）でも言い続ける
analyze build
check_invariant "build(2回目)"
expect_in_warnings "build(2回目)" "src/main/java/sample/app/Broken.java"

# 5a. コンパイルエラー・構文エラーのファイルが多いとき（上限の 20 件を超える）、並べるファイルは
#     キャッシュの状態によらず同じであること。数える順は差分更新では「解析し直したファイル → 書き写したブロック」で、
#     全件解析と違う。先に来た順で残すと、同じソースでも載るファイルと並びが変わる（パスの順で小さいものを残す）
make_project sample ""
SAMPLE_SRC=work/sample/src/main/java/sample/app
for n in Aa Bb Cc Dd Ee Ff Gg Hh Ii Jj Kk Ll Mm Nn Oo Pp Qq Rr Ss Tt Uu Vv Ww Xx Yy; do
    printf 'package sample.app;\n\npublic class Err%s {\n    void run() { new NoSuchType().go(); }\n}\n' "$n" \
        > "$SAMPLE_SRC/Err$n.java"
done
sample_lines() {   # $1=ファイル。ファイルの一覧（「- パス」）と「ほか N 件」の行
    grep -E -e '- src/' -e '- and [0-9]+ more' "$1" 2>/dev/null | sed 's/^\[[^]]*\] //'
}
same_sample() {   # $1=ラベル  $2=全件解析の一覧  $3=差分更新の一覧  $4=先頭に来るはずのファイル  $5=載らないはずのファイル
    if [ -n "$2" ] && grep -q -F "$4" <<< "$2" && ! grep -q -F "$5" <<< "$2" && [ "$2" = "$3" ]; then
        ok "sample: $1 はパスの順で選び、差分更新でも全件解析と同じ"
    else
        ng "sample: $1 が差分更新と全件解析で違う（またはパスの順で選んでいない）"
        diff <(echo "$2") <(echo "$3") | head -6
    fi
}
# コンパイルエラーだけ。warnings.txt の一覧そのものを比べる
analyze sample
check_invariant sample
full_sample=$(sample_lines "$OUT/warnings.txt")
# 並びの最後のファイルだけを書き換える。差分更新ではこれが最初に数えられる
printf '\n// changed\n' >> "$SAMPLE_SRC/ErrYy.java"
analyze sample
same_sample "warnings.txt のコンパイルエラーのファイル" "$full_sample" "$(sample_lines "$OUT/warnings.txt")" \
    "src/main/java/sample/app/ErrAa.java" "src/main/java/sample/app/ErrYy.java"
# 構文エラーも上限を超える。warnings.txt は明細の上限（50 行）で切れるので、同じ一覧を出す run.log で比べる
# （構文エラーのファイルごとの行は解析したファイルにだけ出るので、明細の上限までに載る行は差分更新で変わりうる）
for n in Aa Bb Cc Dd Ee Ff Gg Hh Ii Jj Kk Ll Mm Nn Oo Pp Qq Rr Ss Tt Uu Vv; do
    printf 'package sample.app;\n\npublic class Syn%s {\n    void run( {\n    }\n}\n' "$n" > "$SAMPLE_SRC/Syn$n.java"
done
rm -rf work/sample/.cache
analyze sample
full_sample=$(sample_lines "$OUT/run.log")
printf '\n// changed\n' >> "$SAMPLE_SRC/SynVv.java"
printf '\n// changed again\n' >> "$SAMPLE_SRC/ErrYy.java"
analyze sample
same_sample "run.log のコンパイルエラー・構文エラーのファイル" "$full_sample" "$(sample_lines "$OUT/run.log")" \
    "src/main/java/sample/app/SynAa.java" "src/main/java/sample/app/SynVv.java"

# 5b. var の使い方の誤り（Java 10 より前のコードの class var を、source.level を指定せずに読む）。
#     JDT は構文エラーの印を付けるが、本体は読めている。コンパイルエラーとしては案内し、
#     「本体を読めなかった」とは言わない。呼び出しも出力に出る（docs/syntax-error-report-qa.md の Q7）
make_project varname ""
cat > work/varname/src/main/java/sample/app/var.java <<'EOF'
package sample.app;

public class var {
    void run() {
        Util.count("legacy");
    }
}
EOF
analyze varname
check_invariant varname
expect_in_warnings varname "src/main/java/sample/app/var.java"
if [ -n "$OUT" ] && ! grep -q -F "syntax errors" "$OUT/run.log"; then
    ok "varname: var の使い方の誤りを構文エラー（本体を読めなかった）として数えない"
else
    ng "varname: var の使い方の誤りが構文エラーとして報告された"
fi
grep -q "^at sample.app.var.run(var.java:5),Util.count," "$OUT/call-hierarchy.csv" 2>/dev/null \
    && ok "varname: そのファイルの本体の呼び出しが出力に出る" \
    || ng "varname: そのファイルの本体の呼び出しが出力に無い"

# 5c. 網羅していない switch 式（sealed の許可リストに型を足したのに switch を直していない、など）。JDT は構文エラーの印を
#     付けるが、フロー解析で出るもので本体は読めている。コンパイルエラーとしては案内し、「本体を読めなかった」とは
#     言わない。呼び出しも出力に出る（docs/cache-unification-qa.md の Q63）
make_project switches "source.level=21"
cat > work/switches/src/main/java/sample/app/Shapes.java <<'EOF'
package sample.app;

public class Shapes {
    sealed interface Shape permits Circle, Square, Triangle {}
    record Circle(int r) implements Shape {}
    record Square(int s) implements Shape {}
    record Triangle(int b) implements Shape {}
    enum Color { RED, GREEN }

    int area(Shape shape) {
        return switch (shape) {
            case Circle c -> { Util.count("circle"); yield 1; }
            case Square q -> { Util.count("square"); yield 2; }
        };
    }

    int code(Color color) {
        return switch (color) {
            case RED -> { Util.count("red"); yield 3; }
        };
    }
}
EOF
analyze switches
check_invariant switches
expect_in_warnings switches "src/main/java/sample/app/Shapes.java"
if [ -n "$OUT" ] && ! grep -q -F "syntax errors" "$OUT/run.log"; then
    ok "switches: 網羅していない switch 式を構文エラー（本体を読めなかった）として数えない"
else
    ng "switches: 網羅していない switch 式が構文エラーとして報告された"
fi
grep -q "^at sample.app.Shapes.area(Shapes.java:12),Util.count," "$OUT/call-hierarchy.csv" 2>/dev/null \
    && grep -q "^at sample.app.Shapes.code(Shapes.java:19),Util.count," "$OUT/call-hierarchy.csv" 2>/dev/null \
    && ok "switches: そのファイルの本体の呼び出しが出力に出る" \
    || ng "switches: そのファイルの本体の呼び出しが出力に無い"

# 5d. 式の入れ子が深すぎて JDT のスタックが溢れるファイル（メソッド呼び出しを 1 万段つないだ式）。そのファイルだけを
#     失敗として案内し（warnings.txt の「打ち切られた」）、ほかのファイルは最後まで解析して出力する。以前は
#     StackOverflowError を捕まえておらず、設定 1 つ分の解析がまるごと失敗していた（docs/cache-unification-qa.md の Q62）
make_project deep ""
{
    printf 'package sample.app;\n\npublic class Deep {\n    String chain() {\n        return new StringBuilder()'
    for ((i = 0; i < 10000; i++)); do printf '.append(%d)' "$i"; done
    printf '.toString();\n    }\n}\n'
} > work/deep/src/main/java/sample/app/Deep.java
analyze deep
check_invariant deep
[ "$STATUS" = 0 ] && ok "deep: 1 ファイルのスタックが溢れても、実行は成功する" \
    || ng "deep: 1 ファイルのスタックが溢れて、実行ごと失敗した（終了コード $STATUS）"
expect_in_warnings deep "The analysis or the output stopped partway"
expect_in_warnings deep "src/main/java/sample/app/Deep.java"
expect_in_warnings deep "stack overflow"
grep -q -F "Util.count" "$OUT/call-hierarchy.csv" 2>/dev/null \
    && ok "deep: ほかのファイルの呼び出しは出力に出る" || ng "deep: ほかのファイルの呼び出しが出力に無い"

# 5e. 名前の違う 2 つのファイルで同じ型を宣言している（public でないトップレベルの型）。間に 100 を超えるファイルが
#     あると別々のバッチで解析され、JDT はどちらにもエラーを出さない。片方の呼び出しは出力に出ないので、グラフを
#     組むときに警告する（warnings.txt の「ソースにコンパイルエラーがある」。docs/cache-unification-qa.md の Q61）
make_project twins ""
TWINS=work/twins/src/main/java/sample/app
printf 'package sample.app;\n\npublic class Aaa {\n}\n\nclass Twin {\n    void t() {\n        Util.count("a");\n    }\n}\n' > $TWINS/Aaa.java
printf 'package sample.app;\n\npublic class Zzz {\n}\n\nclass Twin {\n    void t() {\n        Util.count("z");\n    }\n}\n' > $TWINS/Zzz.java
for ((i = 100; i < 220; i++)); do
    printf 'package sample.app;\n\npublic class Fill%d {\n}\n' "$i" > "$TWINS/Fill$i.java"
done
analyze twins
check_invariant twins
expect_in_warnings twins "The type sample.app.Twin is declared in both src/main/java/sample/app/Aaa.java and src/main/java/sample/app/Zzz.java"
# 直し方は、2 つのファイルが同じフォルダにあっても通じる（「どちらかのフォルダだけを書く」だけでは直せない）
expect_in_warnings twins "Remove or rename one of the two declarations"

# 5f. 同じ型の 2 つの宣言に、同じシグネチャのメソッドが 1 つも無い（Twin(int) と a()、暗黙の Twin() と z()）。
#     メソッドの宣言の重なりだけを見ていた f491e2e は警告しなかった。型の宣言（H 行）の重なりで警告する
#     （docs/cache-unification-qa.md の Q72）
make_project twins2 ""
TWINS2=work/twins2/src/main/java/sample/app
printf 'package sample.app;\n\npublic class Aaa {\n}\n\nclass Twin {\n    Twin(int v) {\n    }\n\n    void a() {\n        Util.count("a");\n    }\n}\n' > $TWINS2/Aaa.java
printf 'package sample.app;\n\npublic class Zzz {\n}\n\nclass Twin {\n    void z() {\n        Util.count("z");\n    }\n}\n' > $TWINS2/Zzz.java
for ((i = 100; i < 220; i++)); do
    printf 'package sample.app;\n\npublic class Fill%d {\n}\n' "$i" > "$TWINS2/Fill$i.java"
done
analyze twins2
check_invariant twins2
expect_in_warnings twins2 "The type sample.app.Twin is declared in both src/main/java/sample/app/Aaa.java and src/main/java/sample/app/Zzz.java"

# 5g. 3 つのファイルが同じ型を宣言している（どれも別々のバッチ）。組と文言をキャッシュのブロックの並びに依らせない。
#     最後のファイルだけを書き換えた差分更新では、そのブロックがキャッシュの先頭に移る。出会った順に組を作ると、
#     全件解析と挙げる組が変わる（docs/cache-unification-qa.md の Q72）
make_project twins3 ""
TWINS3=work/twins3/src/main/java/sample/app
printf 'package sample.app;\n\npublic class Aaa {\n}\n\nclass Twin {\n    Twin(int v) {\n    }\n\n    void a() {\n        Util.count("a");\n    }\n}\n' > $TWINS3/Aaa.java
printf 'package sample.app;\n\npublic class Mmm {\n}\n\nclass Twin {\n    void m() {\n        Util.count("m");\n    }\n}\n' > $TWINS3/Mmm.java
printf 'package sample.app;\n\npublic class Zzz {\n}\n\nclass Twin {\n    void z() {\n        Util.count("z");\n    }\n}\n' > $TWINS3/Zzz.java
for ((i = 100; i < 220; i++)); do
    printf 'package sample.app;\n\npublic class Fill%d {\n}\n' "$i" > "$TWINS3/Fill$i.java"
    printf 'package sample.app;\n\npublic class Nfill%d {\n}\n' "$i" > "$TWINS3/Nfill$i.java"
done
twin_lines() {   # $1=warnings.txt。同じ型を宣言するファイルの組の行
    grep -o -E 'The type sample\.app\.Twin is declared in both [^ ]+ and [^ ]+\.java' "$1" 2>/dev/null
}
analyze twins3
check_invariant twins3
full_twins=$(twin_lines "$OUT/warnings.txt")
printf '\n// changed\n' >> "$TWINS3/Zzz.java"
analyze twins3
inc_twins=$(twin_lines "$OUT/warnings.txt")
if [ "$(wc -l <<< "$full_twins")" = 2 ] && grep -q -F "Aaa.java and src/main/java/sample/app/Mmm.java" <<< "$full_twins" \
        && grep -q -F "Aaa.java and src/main/java/sample/app/Zzz.java" <<< "$full_twins" && [ "$full_twins" = "$inc_twins" ]; then
    ok "twins3: 3 つのファイルが同じ型を宣言していても、組（パスの順で最初のファイルとほかのファイル）は差分更新でも全件解析と同じ"
else
    ng "twins3: 同じ型を宣言するファイルの組が期待と違うか、差分更新と全件解析で違います"
    diff <(echo "$full_twins") <(echo "$inc_twins") | head -6
fi

# 6. 実行の失敗（出力フォルダを作った後で失敗する: ソースフォルダが 1 つも無い）
make_project failed "source.folders=src/missing"
analyze failed
[ "$STATUS" -ne 0 ] && ok "failed: 終了コードが 0 以外（$STATUS）" || ng "failed: 失敗したのに終了コードが 0"
check_invariant failed
expect_in_warnings failed "The run failed"

# 7. 表示言語が日本語なら日本語で書く（人が読んで対処を選ぶ案内なので）
make_project deps_ja ""
cp work/deps/pom.xml work/deps_ja/pom.xml
JCHE_LANG=ja analyze deps_ja
check_invariant deps_ja
expect_in_warnings deps_ja "依存 jar が解決できていません"

# 8. jar のクラス（q.Api）が参照するクラス（q.Missing）が無いとき、事実を集めるときの問い合わせが同じバッチの後ろの
#    ファイル（app/B.java）のメソッドを先に解決させ、B の番で JDT が例外を投げて一括解析が落ちていた（「The batch analysis
#    failed」が全件解析の warnings.txt にだけ載った）。今は事実をバッチの全ファイルを JDT が解決し終えてから集める
#    （docs/cache-unification-qa.md の「後ろのファイルの型を先に解決させない」）。A は B の呼び出し（呼び出しの候補）・
#    拡張 for 文・try-with-resources（暗黙の呼び出しの宣言）で B のメソッドを問い合わせる
mkdir -p work/early/src/app work/early/lib work/early/jsrc/q work/early/jcls
printf 'package q;\npublic interface Missing { int X = 1; }\n' > work/early/jsrc/q/Missing.java
printf 'package q;\npublic class Api { public Missing[] probs() { return null; } }\n' > work/early/jsrc/q/Api.java
"$JAVAC_BIN" -d work/early/jcls work/early/jsrc/q/*.java && rm work/early/jcls/q/Missing.class \
    && "$(dirname "$JAVAC_BIN")/jar" cf work/early/lib/q.jar -C work/early/jcls q \
    || ng "early: jar を作れませんでした"
cat > work/early/src/app/A.java <<'EOF'
package app;

public class A {
    public void a(B b) throws Exception {
        b.run();
        for (Object o : b) {
            System.out.println(o);
        }
        try (B r = b) {
            r.run();
        }
    }
}
EOF
cat > work/early/src/app/B.java <<'EOF'
package app;

import q.Missing;

public class B implements Iterable<Object>, AutoCloseable {
    public java.util.Iterator<Object> iterator() {
        return null;
    }

    public void close() {
    }

    public void run() {
    }

    static void w(q.Api a, Missing m) {
    }
}
EOF
cat > work/early/config.properties <<'EOF'
project.root=.
source.folders=src
library.folders=lib
source.encoding=UTF-8
output.folder=./out
cache.folder=./.cache
EOF
analyze early
check_invariant early
if [ -n "$OUT" ] && ! grep -q -F "batch analysis failed" "$OUT/run.log" 2>/dev/null; then
    ok "early: 後ろのファイルの型を先に解決させず、一括解析が落ちない"
else
    ng "early: 一括解析が落ちた（work/early.console.log）"
    grep -a -F "batch analysis failed" "$OUT/run.log" 2>/dev/null | head -2
fi

if [ "$fail" -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
