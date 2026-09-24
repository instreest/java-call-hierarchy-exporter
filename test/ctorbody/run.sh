#!/usr/bin/env bash
# コンストラクタ本体の読み取りの検査（JLS 8.8.7）。
#
#   bash test/ctorbody/run.sh
#
# 見るのは 3 つ。
#   1. 柔軟なコンストラクタ本体（JEP 513。Java 25 で確定）で this(...) の前に文が書ける形を、
#      「委譲していない」と取り違えないこと。取り違えると、インスタンス初期化子の呼び出しが
#      委譲側にも複製されて二重に数えられ、さらに D 行に delegating が付かないため
#      FieldFacts の安全弁（委譲コンストラクタを持つ型ではコンストラクタ引数由来の出所を採らない）
#      が効かなくなる
#   2. 同じ意味の 2 つの書き方（従来形＝先頭が this(...) と、プロローグ付き）が
#      同じ呼び出し階層になること
#   3. インターフェースとアノテーション型に暗黙のコンストラクタを合成しないこと（JLS 8.8.9。
#      デフォルトコンストラクタはクラスにだけある）。クラスには従来どおり合成すること。
#      CSV には <init> が出ないので、キャッシュの D 行で見る（docs/jls-conformance-qa.md の Q25）
#
# この構文は Java 25 でないと書けないので、test/demo には置かない。
# test/demo は多くの検査が共有するうえ、README.md の手順で javac でコンパイルして
# jar を作る（そのとき古い JDK だと落ちる）ためである。ここだけ使い捨ての
# プロジェクトをその場で作って解析する。
#
# ツール本体は javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （test/conditions/run.sh と同じ経路）。
set -uo pipefail
cd "$(dirname "$0")"
# 文言の言語を固定する（既定は英語。固定しないと実行環境のロケールで照合が変わる）
export JCHE_LANG=en
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

# --- 使い捨てのプロジェクトを 2 つ作る（本体は同じ意味で、書き方だけが違う） ---
#   Box(int) … 委譲していない（＝インスタンス初期化子の複製先）
#   Box()    … this(...) で委譲する。こちらには初期化子は複製されない（JLS 8.8.7.1）
make_project() {   # $1=フォルダ名  $2=Box() の本体
    mkdir -p "work/$1/src/g"
    cat > "work/$1/src/g/Helper.java" <<'EOF'
package g;

public class Helper {
    public static int check(int v) { return v; }
    public static int make() { return 7; }
}
EOF
    cat > "work/$1/src/g/Box.java" <<EOF
package g;

public class Box {
    private final int seed = Helper.make();

    Box(int v) { }

    Box() {
$2
    }
}
EOF
    # インターフェース（フィールドは暗黙に static で、初期化は <clinit>）と、それを実装するクラス、
    # アノテーション型。どれもコンストラクタを書かない
    cat > "work/$1/src/g/Shape.java" <<'EOF'
package g;

public interface Shape {
    int SIZE = Helper.check(3);
    int area();
}
EOF
    cat > "work/$1/src/g/Square.java" <<'EOF'
package g;

public class Square implements Shape {
    public int area() { return SIZE; }
}
EOF
    cat > "work/$1/src/g/Tag.java" <<'EOF'
package g;

public @interface Tag {
    String value() default "";
}
EOF
    cat > "work/$1/src/g/Main.java" <<'EOF'
package g;

public class Main {
    public static void main(String[] args) {
        new Box();
        new Box(1);
        new Square().area();
    }
}
EOF
    cat > "work/$1/config.properties" <<EOF
project.root=.
source.folders=src
source.encoding=UTF-8
entry.packages=
output.folder=./out
cache.folder=./.cache
EOF
}

# 従来形: 本体の先頭が this(...)
make_project classic '        this(Helper.check(1));'
# プロローグ付き（JEP 513）: this(...) の前に文がある
make_project prologue '        int v = Helper.check(1);
        this(v);'

analyze() {   # $1=フォルダ名 -> 出力 CSV のパスを ANALYZED に入れる
    ( cd "work/$1" && "$JAVA_BIN" -cp "$CLASSES:$CP" \
        jche.CallHierarchyExporter config.properties ) > "work/$1/run.log" 2>&1
    ANALYZED=$(ls -d "work/$1"/out/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##')
}

for case in classic prologue; do
    analyze $case
    if [ -z "$ANALYZED" ] || [ ! -f "$ANALYZED/call-hierarchy.csv" ]; then
        ng "$case: 解析できませんでした（work/$case/run.log）"; continue
    fi
    grep -q "syntax errors" "work/$case/run.log" \
        && ng "$case: 構文エラーが出ました（JDT が Java 25 の構文を読めていない）" \
        || ok "$case: 構文エラーなしで解析できた"
    cp "$ANALYZED/call-hierarchy.csv" "work/$case.csv"
done

if [ -f work/classic.csv ] && [ -f work/prologue.csv ]; then
    # 突き合わせるのは行の集合。行順は呼び出し箇所のソース上の位置で決まり、
    # プロローグ形は this(...) の行が 1 つ下がるので、並びだけは正しく違う。
    # caller 列の行番号も同じ理由で違うので、比較から外す（2 列目以降を見る）
    strip() { tail -n +2 "$1" | cut -d, -f2- | LC_ALL=C sort; }
    if diff -u <(strip work/classic.csv) <(strip work/prologue.csv) > work/diff.txt; then
        ok "従来形とプロローグ付きで呼び出し階層が一致する"
    else
        ng "書き方だけが違うのに階層が食い違う（this(...) の委譲を取り違えている）"
        sed -n '1,20p' work/diff.txt
    fi

    # 委譲していないコンストラクタは 1 つだけなので、初期化子の Helper.make は
    # 「Box() -> Box(int) -> Helper.make」と「Box(int) -> Helper.make」の 2 経路にだけ出る。
    # 取り違えると Box() に直接ぶら下がる 3 本目が増える
    n=$(grep -c "Helper.make" work/prologue.csv)
    [ "$n" = "2" ] && ok "インスタンス初期化子が委譲コンストラクタに複製されていない（$n 行）" \
        || ng "インスタンス初期化子の複製先が違う（Helper.make が $n 行。期待は 2 行）"
fi

# D 行の delegating（FieldFacts の安全弁がこれを見る）
DELEG=$(grep -P "^D\tg\tg.Box\t<init>\t\t" work/prologue/.cache/*/analysis-cache.tsv 2>/dev/null \
    | grep -c "delegating")
[ "$DELEG" = "1" ] && ok "プロローグ付きでも D 行に delegating が付く" \
    || ng "D 行に delegating が付いていない（FieldFacts の安全弁が効かなくなる）"

# 暗黙のコンストラクタ（JLS 8.8.9）。インターフェースとアノテーション型には無く、クラスには有る
DCACHE=$(ls work/classic/.cache/*/analysis-cache.tsv 2>/dev/null | head -1)
if [ -z "$DCACHE" ]; then
    ng "キャッシュが見つかりません（work/classic/.cache）"
else
    for t in Shape Tag; do
        grep -qP "^D\tg\tg.$t\t<init>\t" "$DCACHE" \
            && ng "$t に暗黙のコンストラクタの D 行がある（インターフェースにコンストラクタは無い）" \
            || ok "$t に暗黙のコンストラクタを合成していない"
    done
    grep -qP "^D\tg\tg.Square\t<init>\t\t.*implicit" "$DCACHE" \
        && ok "クラス（Square）には暗黙のコンストラクタを合成している" \
        || ng "クラス（Square）の暗黙のコンストラクタの D 行が無い"
fi

[ $fail = 0 ] && echo "PASS" || echo "FAIL"
exit $fail
