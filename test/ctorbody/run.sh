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
#      CSV には <init> が出ないので、キャッシュの D 行で見る（docs/jls-conformance-qa.md の Q25）。
#      キャッシュの D 行はメソッドを記号（S 行の番号）で指すので、名前で引けるよう
#      jche.cache.CacheDump で 4 列に戻した形（work/<ケース>/cache-dump.tsv）を見る
#   4. 暗黙の super()（JLS 8.8.7・8.8.9）と匿名コンストラクタ（15.9.5.1）の呼び出し先に、javac が選ぶ
#      コンストラクタが入ること。JDT は暗黙の super() にバインディングを返さないので、ツールが候補を決める。
#      引数なしのものが public・protected でない（見えないことがある）とき・可変長引数 1 つのものが複数あるときは、
#      候補すべてに辺を張る（最も特殊なもの（15.12.2.5）を選ばない）。javac の選ぶものが入っていることと、
#      引数なしのものが public なら可変長引数のほうへ余計な辺を張らないことを見る。匿名クラスは、JDT が選んだ
#      コンストラクタの引数の並びを匿名コンストラクタが持つので、型引数を置き換えた親のメンバーと比べて 1 つに
#      決まる（ジェネリックなコンストラクタ・ジェネリックな外側のクラスの内部クラスも）。期待値はどれも javac で
#      確かめた呼び出し先（javap の invokespecial）
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

analyze() {   # $1=フォルダ名 -> 出力 CSV のパスを ANALYZED に入れる。キャッシュの読める形を work/$1/cache-dump.tsv に
    ( cd "work/$1" && "$JAVA_BIN" -cp "$CLASSES:$CP" \
        jche.CallHierarchyExporter config.properties ) > "work/$1/run.log" 2>&1
    ANALYZED=$(ls -d "work/$1"/out/*/ 2>/dev/null | sort | tail -1 | sed 's#/$##')
    local cache
    cache=$(ls "work/$1"/.cache/*/analysis-cache.tsv 2>/dev/null | head -1)
    rm -f "work/$1/cache-dump.tsv"
    if [ -z "$cache" ]; then
        ng "$1: キャッシュができていません（work/$1/run.log）"
    elif ! "$JAVA_BIN" -cp "$CLASSES:$CP" jche.cache.CacheDump "$cache" > "work/$1/cache-dump.tsv" \
            2> "work/$1/cache-dump.log"; then
        # 読める形にできなかった。途中までの出力が残ると、下の「無いこと」の検査が素通りするので消す
        ng "$1: キャッシュを読める形にできませんでした（work/$1/cache-dump.log）"
        grep -v JAVA_TOOL_OPTIONS "work/$1/cache-dump.log" | tail -5
        rm -f "work/$1/cache-dump.tsv"
    fi
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
DELEG=$(grep -P "^D\tg\tg.Box\t<init>\t\t" work/prologue/cache-dump.tsv 2>/dev/null \
    | grep -c "delegating")
[ "$DELEG" = "1" ] && ok "プロローグ付きでも D 行に delegating が付く" \
    || ng "D 行に delegating が付いていない（FieldFacts の安全弁が効かなくなる）"

# 暗黙のコンストラクタ（JLS 8.8.9）。インターフェースとアノテーション型には無く、クラスには有る
DCACHE=$(ls work/classic/cache-dump.tsv 2>/dev/null | head -1)
if [ -z "$DCACHE" ] || [ ! -s "$DCACHE" ]; then
    ng "キャッシュ（の読める形）が見つかりません（work/classic/.cache・work/classic/cache-dump.tsv）"
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

# --- 4. 暗黙の super() と匿名コンストラクタの呼び出し先 ---
mkdir -p work/isuper/src/q work/isuper/src/u
cat > work/isuper/src/q/Log.java <<'EOF'
package q;

public class Log {
    public static void foo() { }
    public static void bar() { }
    public static void hidden() { }
    public static void varargs() { }
    public static void priv() { }
    public static void strings() { }
    public static void ints() { }
    public static void longs() { }
    public static void pub() { }
    public static void pubVarargs() { }
    public static void gen() { }
    public static void genNoArg() { }
    public static void in() { }
}
EOF
cat > work/isuper/src/q/Bar.java <<'EOF'
package q;

public class Bar { }
EOF
cat > work/isuper/src/q/Foo.java <<'EOF'
package q;

public class Foo extends Bar { }
EOF
# Foo... が Bar... より特殊（javac は Base(Foo[]) を呼ぶ）。宣言の順に依らない
cat > work/isuper/src/q/Base.java <<'EOF'
package q;

public class Base {
    public Base(Foo... f) { Log.foo(); }
    public Base(Bar... b) { Log.bar(); }
}
EOF
# 引数なしのものはパッケージ private で、別のパッケージの部分型からは見えない（javac は Base2(Object[]) を呼ぶ）
cat > work/isuper/src/q/Base2.java <<'EOF'
package q;

public class Base2 {
    Base2() { Log.hidden(); }
    public Base2(Object... o) { Log.varargs(); }
}
EOF
# 引数なしのものは private で、別のトップレベルのクラスからは見えない（javac は Base3(String[]) を呼ぶ）
cat > work/isuper/src/q/Base3.java <<'EOF'
package q;

public class Base3 {
    private Base3() { Log.priv(); }
    protected Base3(String... s) { Log.strings(); }
}
EOF
cat > work/isuper/src/q/S3.java <<'EOF'
package q;

public class S3 extends Base3 {
    S3(int x) { }
    public static void make() { new S3(1); }
}
EOF
# int... が long... より特殊（JLS 4.10.1。javac は Base4(int[]) を呼ぶ）
cat > work/isuper/src/q/Base4.java <<'EOF'
package q;

public class Base4 {
    public Base4(int... a) { Log.ints(); }
    public Base4(long... a) { Log.longs(); }
}
EOF
# 引数なしのものが public なら第 1 段で決まる。可変長引数のほうへは辺を張らない
cat > work/isuper/src/q/Base5.java <<'EOF'
package q;

public class Base5 {
    public Base5() { Log.pub(); }
    public Base5(String... s) { Log.pubVarargs(); }
}
EOF
# ジェネリックなコンストラクタと、ジェネリックな外側のクラスの内部クラス（匿名クラスの親）
cat > work/isuper/src/q/G.java <<'EOF'
package q;

public abstract class G {
    protected G() { Log.genNoArg(); }
    protected <X> G(X x) { Log.gen(); }
}
EOF
cat > work/isuper/src/q/Outer.java <<'EOF'
package q;

public class Outer<T> {
    public abstract class In {
        protected In(T t) { Log.in(); }
    }
}
EOF
cat > work/isuper/src/u/S.java <<'EOF'
package u;

public class S extends q.Base { }
EOF
cat > work/isuper/src/u/S2.java <<'EOF'
package u;

public class S2 extends q.Base2 {
    public S2() { System.out.println(); }
}
EOF
cat > work/isuper/src/u/S4.java <<'EOF'
package u;

public class S4 extends q.Base4 { }
EOF
cat > work/isuper/src/u/S5.java <<'EOF'
package u;

public class S5 extends q.Base5 { }
EOF
cat > work/isuper/src/u/Main.java <<'EOF'
package u;

import q.Foo;
import q.G;
import q.Outer;

public class Main {
    public static void main(String[] args) {
        new S();
        new S2();
        new S4();
        new S5();
    }

    static Object gen(Foo f) {
        return new G(f) { };
    }

    static Object inner(Outer<Foo> o, Foo f) {
        return o.new In(f) { };
    }
}
EOF
cat > work/isuper/config.properties <<'EOF'
project.root=.
source.folders=src
source.encoding=UTF-8
entry.packages=
output.folder=./out
cache.folder=./.cache
EOF
analyze isuper
if [ -z "$ANALYZED" ] || [ ! -f "$ANALYZED/call-hierarchy.csv" ]; then
    ng "isuper: 解析できませんでした（work/isuper/run.log）"
else
    ISCSV="$ANALYZED/call-hierarchy.csv"
    # 行に ",<階層>" が、行末か次の "," の前で終わる形で現れるか（Log.gen が Log.genNoArg に当たらないように）
    path_in_csv() {
        awk -v p=",$1" 'index($0 ",", p ",") > 0 { found = 1 } END { exit !found }' "$ISCSV"
    }
    has_path() {   # $1=root 列から続く呼び出し階層（カンマ区切り）  $2=説明
        if path_in_csv "$1"; then ok "$2"; else ng "$2（$1 が call-hierarchy.csv に無い）"; fi
    }
    no_path() {
        if path_in_csv "$1"; then ng "$2（$1 が call-hierarchy.csv にある）"; else ok "$2"; fi
    }
    has_path 'Main.main,S.S,Base.Base,Log.foo' '暗黙の super() が最も特殊な可変長引数のコンストラクタ Base(Foo...) に届く'
    has_path 'Main.main,S2.S2,Base2.Base2,Log.varargs' \
        '引数なしのものが見えない（パッケージ private）とき、暗黙の super() が可変長引数のコンストラクタに届く'
    has_path 'S3.make,S3.S3,Base3.Base3,Log.strings' \
        '引数なしのものが見えない（private）とき、暗黙の super() が可変長引数のコンストラクタに届く'
    has_path 'Main.main,S4.S4,Base4.Base4,Log.ints' '暗黙の super() が Base4(int...) に届く（long... より特殊）'
    has_path 'Main.main,S5.S5,Base5.Base5,Log.pub' '引数なしのものが public なら、暗黙の super() はそれを呼ぶ'
    no_path 'Main.main,S5.S5,Base5.Base5,Log.pubVarargs' \
        '引数なしのものが public なら、可変長引数のコンストラクタへは辺を張らない'
    has_path 'Main.gen,Main$1.Main$1,G.G,Log.gen' '匿名クラスがジェネリックなコンストラクタ <X> G(X) に届く'
    no_path 'Main$1.Main$1,G.G,Log.genNoArg' '匿名クラスは引数の数の違う G() へは辺を張らない'
    has_path 'Main.inner,Main$2.Main$2,Outer.In.In,Log.in' \
        '匿名クラスがジェネリックな外側のクラスの内部クラスのコンストラクタ In(T) に届く'
fi

[ $fail = 0 ] && echo "PASS" || echo "FAIL"
exit $fail
