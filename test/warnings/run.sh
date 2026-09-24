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

if [ "$fail" -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
