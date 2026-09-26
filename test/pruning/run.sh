#!/usr/bin/env bash
# 呼び出しを黙って落とさないことの検査（条件分岐の打ち切り・具象クラスの絞り込みの材料になる値）。
#
#   bash test/pruning/run.sh
#
# 値（定数・引数・new した型）を読み違えると、読み手は「この経路では呼ばれない」（[UNREACHABLE]）や
# 「この実装だけ」（LOCAL_NEW・DATAFLOW_*）と言い切ってしまい、実際には動く呼び出しがその先の階層ごと
# 出力から消える。エラーにも警告にもならない。そこで、読み違えやすい書き方ごとに小さなクラスを 1 つ書き、
# call-hierarchy.csv の行で見る。
#
#   - 落としてはいけない（reachable / listed）… 以前は誤って打ち切り・絞り込みをしていた書き方
#   - 打ち切られなければならない（pruned / resolved）… 対照。仕組みごと効かなくして通すことを防ぐ
#     （真偽値の定数・int の定数・16 進の定数・文字列 / ボックス型 / 列挙型の equals・enum の switch・
#       書き換えない変数の別名・定数フィールドを写した変数・| を含む文字列の定数・new だけのローカル変数・
#       コンストラクタで受け取るフィールド・識別子の形の文字列を返すメソッド・契約表のキーになる戻り値・
#       上書きされないメソッド（static・private・final・継承だけ）の戻り値・上書きされない @Bean メソッド・
#       入れ子のクラスが別のフィールドにだけ書くフィールド・this.m() と this.f・空の new に add しただけのリスト・
#       static メソッドの参照の実引数・private / static のメソッドへの invoke・Bean のコンストラクタと @Autowired の
#       メソッドの引数・別のインスタンスのフィールドでもどのインスタンスでも同じ値（初期化子の new・コンストラクタで入れるラムダ）・
#       フレームワークが書かないフィールドの初期化子（java.lang の注釈だけのフィールド・ステレオタイプの Bean の注釈の無いフィールド）・
#       よその型が別のフィールドにだけ書く Bean のフィールド・Objects.requireNonNull で包んだコンストラクタ注入・
#       値を読まない指定で、型の当たらないフィールドにだけ new を入れる Bean のフィールド）
#
# ケースを足すときは case_ を 1 回呼ぶ（ソースは標準入力。クラス 1 つで、main を起点にする）。
# 同じクラスの別の行も見るときは、続けて expect_ を呼ぶ。
# 起点は「呼び出し元が無いメソッド」（entry.packages が空）なので、ケースどうしは混ざらない。
# 契約表（work/contracts.txt）は KeyFactory・SepFactory・EnumKeyFac・TwoKeyFactory の行だけで、それを使うケースにしか効かない。
# DI（Spring）の Bean 登録（@Bean・@Repository）は Sb・Sk・Sn で始まる型だけに付ける（Dao の実装を Bean にすると、引数やフィールドで
# 受け取った Dao の呼び出しがすべて段 5 で絞られ、他のケースが変わってしまうため）。
#
# ツール本体は javac でコンパイルし、jbang が用意した JDK 25 と JDT の jar で動かす
# （test/ctorbody/run.sh と同じ経路。JCHE_CP / JCHE_CLASSES / JCHE_JAVA / JCHE_JAVAC で差し替えられる）。
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

SRC=work/src/pr
mkdir -p "$SRC"
cat > work/config.properties <<'EOF'
project.root=.
source.folders=src
source.encoding=UTF-8
entry.packages=
output.folder=./out
cache.folder=./.cache
contracts.files=contracts.txt
EOF
# ファクトリに渡したキーで絞る契約（KeyFactory・SepFactory・EnumKeyFac・TwoKeyFactory を使うケースだけに効く）。
# SepFactory の行はキーが出所の文法の文字（| と ;）を含む。どちらの行に当たったかで、キーを切って読んだかが分かる。
# TwoKeyFactory は 2 つのキーを受け取り、表には 1 つのキーの行しか無い。どの行に当たったかで、実引数を
# どの位置から読んだかが分かる
cat > work/contracts.txt <<'EOF'
pr.KeyFactory#get("A") => pr.DaoA
pr.SepFactory#get("USER") => pr.DaoA
pr.SepFactory#get("USER|X") => pr.DaoB
pr.SepFactory#get("S") => pr.DaoA
pr.SepFactory#get("S;T") => pr.DaoB
pr.EnumKeyFac#get("pr.EkMode.X") => pr.DaoA
pr.TwoKeyFactory#get("A") => pr.DaoA
pr.TwoKeyFactory#get("B") => pr.DaoB
EOF

# 共通の型。Dao の実装が 2 つあり、どちらが動くかを絞り込みが決める
cat > "$SRC/Dao.java" <<'EOF'
package pr;

public interface Dao { void find(); }
EOF
cat > "$SRC/DaoA.java" <<'EOF'
package pr;

public class DaoA implements Dao { public void find() { System.out.println("a"); } }
EOF
cat > "$SRC/DaoB.java" <<'EOF'
package pr;

public class DaoB implements Dao { public void find() { System.out.println("b"); } }
EOF
cat > "$SRC/Task.java" <<'EOF'
package pr;

public abstract class Task {
    private final Dao seed;
    protected Task(Dao seed) { this.seed = seed; }
    abstract void go();
    Dao seed() { return seed; }
}
EOF
# クラス名の文字列から生成するファクトリ（Class.forName。実引数の文字列で具象クラスが決まる）
cat > "$SRC/Factory.java" <<'EOF'
package pr;

public class Factory {
    static Dao create(String cls) throws Exception {
        return (Dao) Class.forName(cls).getDeclaredConstructor().newInstance();
    }
}
EOF
# 契約表だけが実装を決めるファクトリ（戻り値が 2 通り）。キーが | ; を含むケースで使う
cat > "$SRC/SepFactory.java" <<'EOF'
package pr;

public class SepFactory {
    public static Dao get(String key) {
        if (key.isEmpty()) { return new DaoA(); }
        return new DaoB();
    }
}
EOF
# DI（Spring）の注釈。単純名で照合するので、同じ名前の注釈型をここで宣言すれば Spring の jar は要らない
cat > "$SRC/Bean.java" <<'EOF'
package pr;

@interface Bean { }
EOF
cat > "$SRC/Autowired.java" <<'EOF'
package pr;

@interface Autowired { boolean required() default true; }
EOF
# フレームワークが生成の後にフィールドへ書く注釈（docs/value-safety-qa.md の Q27）。Component はステレオタイプなので、
# ほかの DI の注釈と同じく Sb・Sk・Sn で始まる型にだけ付ける
# Inject は既にケースのクラス名（pr.Inject）なので、別のパッケージに置く（単純名で照合するので、どこにあってもよい）
mkdir -p "$SRC/di"
cat > "$SRC/di/Inject.java" <<'EOF'
package pr.di;

public @interface Inject { }
EOF
cat > "$SRC/Resource.java" <<'EOF'
package pr;

@interface Resource { String name() default ""; }
EOF
cat > "$SRC/Value.java" <<'EOF'
package pr;

@interface Value { String value(); }
EOF
cat > "$SRC/Component.java" <<'EOF'
package pr;

@interface Component { }
EOF
cat > "$SRC/ConfigurationProperties.java" <<'EOF'
package pr;

@interface ConfigurationProperties { String value() default ""; }
EOF
cat > "$SRC/Repository.java" <<'EOF'
package pr;

@interface Repository { }
EOF
# キーの文字列で具象クラスを返すファクトリ。契約表の 1 行（"A" => DaoA）で絞れる
cat > "$SRC/KeyFactory.java" <<'EOF'
package pr;

public class KeyFactory {
    public static Dao get(String key) {
        if (key.equals("A")) { return new DaoA(); }
        return new DaoB();
    }
}
EOF
# 2 つのキーを受け取るファクトリ（戻り値が 2 通り。契約表の TwoKeyFactory の行だけが実装を決める）
cat > "$SRC/TwoKeyFactory.java" <<'EOF'
package pr;

public class TwoKeyFactory {
    public static Dao get(String k1, String k2) {
        if (k1.isEmpty()) { return new DaoA(); }
        return new DaoB();
    }
}
EOF

# ケースの一覧（解析の後で順に確かめる）。1 行 = 期待<TAB>呼び出し元<TAB>呼び出し先<TAB>説明
CASES=()

# case_ <期待> <クラス名> <呼び出し元 Class.method> <呼び出し先 Class.method> <説明>
#   ソース（package pr のクラス <クラス名>）を標準入力で渡す。期待は次のどれか:
#     reachable      その呼び出しの行があり、[UNREACHABLE] が付かない（打ち切ってはいけない）
#     pruned         その呼び出しの行があり、[UNREACHABLE] が付く（打ち切られるべき。対照）
#     listed         その呼び出しの行が候補として出る（絞り込みで落ちていない）
#     resolved:<種別> その行の resolved-by が <種別>（RESOLVED:LOCAL_NEW など。絞り込みが効く対照）
#     absent         その呼び出しの行が無い（絞り込みで別の実装に決まった対照）
#     pruned=<値>    pruned に加えて、打ち切りの注記の値が <値>（注記の「= <値>)」。値を切らずに読んだことを見る）
#     from:<起点>    その呼び出しの行のうち、起点（root 列）が <起点> の行がある（呼び出し先がどこからも呼ばれず
#                    それ自身が起点になった行と区別する。リフレクションで繋ぐ先を取り違えると、正しい先は起点に回る）
#     via:<Class.method>:<期待>  経路（root 列と call-hierarchy 列）に <Class.method> を通る行だけで <期待> を見る
#                    （メソッド参照は、参照を書いたメソッドから参照先への辺も持つ。関数型インターフェースを
#                    呼んだ側（run の c.accept）から降りた経路だけを見たいときに使う）
#   呼び出し元の Class には匿名・ローカルクラスの名前（Leak$1）も書ける。
#   クラス名を / で始めると無名パッケージのクラスになる（/Kind なら work/src/Kind.java。呼び出し元も /Kind.m と書く）
case_() {
    local expect=$1 cls=$2 caller=$3 callee=$4 desc=$5
    if [ "${cls#/}" != "$cls" ]; then
        cat > "work/src/${cls#/}.java"
    else
        cat > "$SRC/$cls.java"
    fi
    CASES+=("$expect	$caller	$callee	$desc")
}

# expect_ <期待> <呼び出し元 Class.method> <呼び出し先 Class.method> <説明>
#   ソースを書かずに期待だけを足す。直前の case_ のクラスで、別の呼び出し先も見るときに使う
#   （同じ呼び出しから複数の実装が出ることを確かめる。片方だけを見ると、もう片方を落としても通ってしまう）
expect_() {
    CASES+=("$1	$2	$3	$4")
}

# ---------------------------------------------------------------------------
# 値を変えるキャスト（JLS 5.1.3）。(byte)300 は 44
# ---------------------------------------------------------------------------
case_ reachable Cast Cast.run Cast.hit "(byte)300 を渡した経路で mode == 44 を打ち切らない（定数は畳んだ値 44）" <<'EOF'
package pr;

public class Cast {
    public static void main(String[] args) { run((byte) 300); }
    static void run(byte mode) { if (mode == 44) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable CastArg CastArg.run CastArg.hit "(byte) p を実引数にした経路で、p の値（300）を当てて打ち切らない" <<'EOF'
package pr;

public class CastArg {
    public static void main(String[] args) { outer(300); }
    static void outer(int p) { run((byte) p); }
    static void run(int mode) { if (mode == 44) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable CastLocal CastLocal.alias CastLocal.hit "int m = (byte) p; の m を p の別名にしない" <<'EOF'
package pr;

public class CastLocal {
    public static void main(String[] args) { alias(300); }
    static void alias(int p) {
        int m = (byte) p;
        if (m == 44) { hit(); }
    }
    static void hit() { System.out.println("h"); }
}
EOF

case_ pruned CastFold CastFold.run CastFold.hit "対照: (byte)301 は 45 なので mode == 44 は打ち切る（畳んだ値で判定する）" <<'EOF'
package pr;

public class CastFold {
    public static void main(String[] args) { run((byte) 301); }
    static void run(byte mode) { if (mode == 44) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ pruned Widen Widen.wide Widen.six "対照: 値を保つ拡大（(long) v）は剥がして判定する" <<'EOF'
package pr;

public class Widen {
    public static void main(String[] args) { wide(5); }
    static void wide(int v) {
        long w = (long) v;
        if (w == 6) { six(); }
    }
    static void six() { System.out.println("6"); }
}
EOF

# ---------------------------------------------------------------------------
# 浮動小数（1.0 と 1 は表記が違うだけで同じ値）
# ---------------------------------------------------------------------------
case_ reachable Dbl Dbl.dbl Dbl.one "dbl(1.0) の経路で x == 1 を打ち切らない" <<'EOF'
package pr;

public class Dbl {
    public static void main(String[] args) { dbl(1.0); }
    static void dbl(double x) { if (x == 1) { one(); } }
    static void one() { System.out.println("one"); }
}
EOF

case_ reachable FloatWide FloatWide.f FloatWide.hit "int から float への暗黙の変換は精度を失う（16777217f == 16777216f）" <<'EOF'
package pr;

public class FloatWide {
    public static void main(String[] args) { f(16777217); }
    static void f(float x) { if (x == 16777216) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

# ---------------------------------------------------------------------------
# 最上位ビットが立つ 16 進・8 進の int リテラル（JLS 3.10.1。0x80000000 は -2147483648）
# 条件の期待値と呼び出し元の値の両方を JDT の値で持たないと、片方だけ正の値に見える
# ---------------------------------------------------------------------------
case_ reachable Hex Hex.run Hex.hit "run(0x80000000) の経路で flags == 0x80000000 を打ち切らない" <<'EOF'
package pr;

public class Hex {
    public static void main(String[] args) { run(0x80000000); }
    static void run(int flags) { if (flags == 0x80000000) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable Oct Oct.run Oct.hit "8 進でも同じ（020000000000 は -2147483648）" <<'EOF'
package pr;

public class Oct {
    public static void main(String[] args) { run(020000000000); }
    static void run(int flags) { if (flags == 020000000000) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable HexConst HexConst.run HexConst.hit "static final int MASK = 0xFF000000 を渡した経路で flags == 0xFF000000 を打ち切らない" <<'EOF'
package pr;

public class HexConst {
    static final int MASK = 0xFF000000;
    public static void main(String[] args) { run(MASK); }
    static void run(int flags) { if (flags == 0xFF000000) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable HexDec HexDec.run HexDec.hit "10 進の -16 を渡した経路で flags == 0xFFFFFFF0 を打ち切らない" <<'EOF'
package pr;

public class HexDec {
    public static void main(String[] args) { run(-16); }
    static void run(int flags) { if (flags == 0xFFFFFFF0) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable HexSwitch HexSwitch.run HexSwitch.hit "switch の case ラベルでも同じ（case 0x80000000）" <<'EOF'
package pr;

public class HexSwitch {
    public static void main(String[] args) { run(0x80000000); }
    static void run(int flags) {
        switch (flags) {
            case 0x80000000 -> hit();
            default -> other();
        }
    }
    static void hit() { System.out.println("h"); }
    static void other() { System.out.println("o"); }
}
EOF
expect_ pruned HexSwitch.run HexSwitch.other "対照: 同じ switch の default は打ち切る（値が case と一致する）"

case_ pruned HexOther HexOther.run HexOther.hit "対照: run(0x80000001) の経路では flags == 0x80000000 を打ち切る" <<'EOF'
package pr;

public class HexOther {
    public static void main(String[] args) { run(0x80000001); }
    static void run(int flags) { if (flags == 0x80000000) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

# ---------------------------------------------------------------------------
# 対照: 仕組みごとに、打ち切られなければならない形
# ---------------------------------------------------------------------------
case_ pruned Flag Flag.flag Flag.x "対照: 真偽値の定数（flag(false) の if (b)）" <<'EOF'
package pr;

public class Flag {
    public static void main(String[] args) { flag(false); }
    static void flag(boolean b) { if (b) { x(); } }
    static void x() { System.out.println("x"); }
}
EOF

case_ pruned Num Num.num Num.numOne "対照: int の定数（num(2) の n == 1）" <<'EOF'
package pr;

public class Num {
    public static void main(String[] args) { num(2); }
    static void num(int n) { if (n == 1) { numOne(); } }
    static void numOne() { System.out.println("1"); }
}
EOF

case_ pruned Str Str.str Str.full "対照: 文字列の equals（str(\"light\") の \"full\".equals(s)）" <<'EOF'
package pr;

public class Str {
    public static void main(String[] args) { str("light"); }
    static void str(String s) { if ("full".equals(s)) { full(); } }
    static void full() { System.out.println("f"); }
}
EOF

case_ pruned StrLocal StrLocal.str StrLocal.full "対照: ローカル変数に入れた文字列（識別子の形でない \"light mode\"）も値として渡る" <<'EOF'
package pr;

public class StrLocal {
    public static void main(String[] args) { run(); }
    static void run() {
        String mode = "light mode";
        str(mode);
    }
    static void str(String s) { if ("full".equals(s)) { full(); } }
    static void full() { System.out.println("f"); }
}
EOF

case_ pruned Sel Sel.sel Sel.ea "対照: enum の switch（sel(Mode.B) の case A）" <<'EOF'
package pr;

public class Sel {
    enum Mode { A, B }
    public static void main(String[] args) { sel(Mode.B); }
    static void sel(Mode m) {
        switch (m) {
            case A -> ea();
            case B -> eb();
        }
    }
    static void ea() { System.out.println("a"); }
    static void eb() { System.out.println("b"); }
}
EOF

# ---------------------------------------------------------------------------
# equals は実行時の型が違えば偽（Long.valueOf(0).equals(0) は偽）。値の表記だけで判定しない
# ---------------------------------------------------------------------------
case_ reachable EqLong EqLong.check EqLong.hit "Long の id と int の定数（!id.equals(0)）は、check(0L) でも成立する" <<'EOF'
package pr;

public class EqLong {
    public static void main(String[] args) { check(0L); }
    static void check(Long id) { if (!id.equals(0)) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable EqObjLong EqObjLong.m EqObjLong.hit "Object に 5L が入る経路で !o.equals(5) を打ち切らない" <<'EOF'
package pr;

public class EqObjLong {
    public static void main(String[] args) { m(5L); }
    static void m(Object o) { if (!o.equals(5)) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable EqObjChar EqObjChar.m EqObjChar.hit "Object に 'A' が入る経路で !o.equals(65) を打ち切らない（char は数値で持つ）" <<'EOF'
package pr;

public class EqObjChar {
    public static void main(String[] args) { m('A'); }
    static void m(Object o) { if (!o.equals(65)) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable EqStrObj EqStrObj.m EqStrObj.hit "Object に 5 が入る経路で !\"5\".equals(o) を打ち切らない" <<'EOF'
package pr;

public class EqStrObj {
    public static void main(String[] args) { m(5); }
    static void m(Object o) { if (!"5".equals(o)) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable EqObjDbl EqObjDbl.m EqObjDbl.hit "double に拡大した 1 が Object に入る経路で !o.equals(1) を打ち切らない" <<'EOF'
package pr;

public class EqObjDbl {
    public static void main(String[] args) { double d = 1; m(d); }
    static void m(Object o) { if (!o.equals(1)) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ pruned EqStrRecv EqStrRecv.str EqStrRecv.full "対照: String の相手と文字列の定数（s.equals(\"full\")）は判定する" <<'EOF'
package pr;

public class EqStrRecv {
    public static void main(String[] args) { str("light"); }
    static void str(String s) { if (s.equals("full")) { full(); } }
    static void full() { System.out.println("f"); }
}
EOF

case_ pruned EqBoxed EqBoxed.m EqBoxed.five "対照: Integer の相手と int の定数（i.equals(5)）は判定する" <<'EOF'
package pr;

public class EqBoxed {
    public static void main(String[] args) { m(6); }
    static void m(Integer i) { if (i.equals(5)) { five(); } }
    static void five() { System.out.println("5"); }
}
EOF

case_ pruned EqLongL EqLongL.check EqLongL.one "対照: Long の相手と long の定数（id.equals(1L)）は判定する" <<'EOF'
package pr;

public class EqLongL {
    public static void main(String[] args) { check(0L); }
    static void check(Long id) { if (id.equals(1L)) { one(); } }
    static void one() { System.out.println("1"); }
}
EOF

case_ pruned EqEnum EqEnum.sel EqEnum.ea "対照: 同じ列挙型の相手と列挙定数（m.equals(Mode.A)）は判定する" <<'EOF'
package pr;

public class EqEnum {
    enum Mode { A, B }
    public static void main(String[] args) { sel(Mode.B); }
    static void sel(Mode m) { if (m.equals(Mode.A)) { ea(); } }
    static void ea() { System.out.println("a"); }
}
EOF

# ---------------------------------------------------------------------------
# ループの中で、後ろの代入が前の行に届く（先読みは表が変わらなくなるまで読み直す）
# ---------------------------------------------------------------------------
case_ reachable AliasCount AliasCount.count AliasCount.notFirst "int copy = n; の後の n++ で copy も 0 のままではなくなる" <<'EOF'
package pr;

public class AliasCount {
    public static void main(String[] args) { count(args); }
    static void count(String[] items) {
        int n = 0;
        for (String s : items) {
            int copy = n;
            if (copy != 0) { notFirst(); }
            n++;
        }
    }
    static void notFirst() { System.out.println("n"); }
}
EOF

case_ reachable AliasLoop AliasLoop.run AliasLoop.hit "int y = x; の後の x = 5 は、次の周の y に届く" <<'EOF'
package pr;

public class AliasLoop {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        int x = 0;
        for (int k = 0; k < times; k++) {
            int y = x;
            if (y == 5) { hit(); }
            x = 5;
        }
    }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable AliasArg AliasArg.check AliasArg.hit "check(x) の後の x = 5 は、次の周の実引数に届く" <<'EOF'
package pr;

public class AliasArg {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        int x = 0;
        for (int k = 0; k < times; k++) {
            check(x);
            x = 5;
        }
    }
    static void check(int v) { if (v == 5) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable AliasStr AliasStr.str AliasStr.full "String cur = mode; の後の mode = \"full mode\" は、次の周の実引数に届く（値はノードだけが持つ）" <<'EOF'
package pr;

public class AliasStr {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        String mode = "light mode";
        for (int k = 0; k < times; k++) {
            String cur = mode;
            str(cur);
            mode = "full mode";
        }
    }
    static void str(String s) { if ("full mode".equals(s)) { full(); } }
    static void full() { System.out.println("f"); }
}
EOF

case_ listed AliasNew AliasNew.run DaoA.find "Dao y = x; の後の x = new DaoB() で、y.find() は DaoA と DaoB の両方" <<'EOF'
package pr;

public class AliasNew {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        Dao x = new DaoA();
        for (int k = 0; k < times; k++) {
            Dao y = x;
            y.find();
            x = new DaoB();
        }
    }
}
EOF
expect_ listed AliasNew.run DaoB.find "同上（後ろで代入した DaoB）"

case_ listed WrapLoop WrapLoop.run DaoA.find "Dao y = wrap(x); の後の x = new DaoB() で、y.find() は DaoA と DaoB の両方" <<'EOF'
package pr;

public class WrapLoop {
    public static void main(String[] args) { run(args.length); }
    static Dao wrap(Dao d) { return d; }
    static void run(int times) {
        Dao x = new DaoA();
        for (int k = 0; k < times; k++) {
            Dao y = wrap(x);
            y.find();
            x = new DaoB();
        }
    }
}
EOF
expect_ listed WrapLoop.run DaoB.find "同上（後ろで代入した DaoB）"

case_ listed FactoryLoop FactoryLoop.run DaoA.find "Factory.create(name) の後の name = \"pr.DaoB\" で、d.find() は DaoA と DaoB の両方" <<'EOF'
package pr;

public class FactoryLoop {
    public static void main(String[] args) throws Exception { run(args.length); }
    static void run(int times) throws Exception {
        String name = "pr.DaoA";
        for (int k = 0; k < times; k++) {
            Dao d = Factory.create(name);
            d.find();
            name = "pr.DaoB";
        }
    }
}
EOF
expect_ listed FactoryLoop.run DaoB.find "同上（後ろで代入したクラス名の DaoB）"

case_ listed FactoryAlias FactoryAlias.run DaoA.find "String cur = name; を挟んでも同じ" <<'EOF'
package pr;

public class FactoryAlias {
    public static void main(String[] args) throws Exception { run(args.length); }
    static void run(int times) throws Exception {
        String name = "pr.DaoA";
        for (int k = 0; k < times; k++) {
            String cur = name;
            Dao d = Factory.create(cur);
            d.find();
            name = "pr.DaoB";
        }
    }
}
EOF
expect_ listed FactoryAlias.run DaoB.find "同上（後ろで代入したクラス名の DaoB）"

case_ listed LoopVarInner LoopVarInner.run DaoA.find "拡張 for の変数を内側のループで書き換えると、写した y は要素（DaoA）だけではない" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class LoopVarInner {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        for (Dao x : list) {
            for (int k = 0; k < times; k++) {
                Dao y = x;
                y.find();
                x = new DaoB();
            }
        }
    }
}
EOF
expect_ listed LoopVarInner.run DaoB.find "同上（内側のループで代入した DaoB）"

case_ listed ElemLate ElemLate.run DaoA.find "list.add(d) の後の d = new DaoB() は、次の周の拡張 for の要素に届く" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemLate {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        List<Dao> list = new ArrayList<>();
        Dao d = new DaoA();
        for (int k = 0; k < times; k++) {
            for (Dao x : list) { x.find(); }
            list.add(d);
            d = new DaoB();
        }
    }
}
EOF
expect_ listed ElemLate.run DaoB.find "同上（後ろで代入した DaoB）"

case_ pruned AliasConst AliasConst.run AliasConst.hit "対照: 書き換えない変数の別名（int y = x;）は定数として打ち切る" <<'EOF'
package pr;

public class AliasConst {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        int x = 0;
        for (int k = 0; k < times; k++) {
            int y = x;
            if (y == 5) { hit(); }
        }
    }
    static void hit() { System.out.println("h"); }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_NEW AliasNewOnly AliasNewOnly.run DaoA.find "対照: 書き換えない変数の別名（Dao y = x;）は new した型に絞る" <<'EOF'
package pr;

public class AliasNewOnly {
    public static void main(String[] args) { run(args.length); }
    static void run(int times) {
        Dao x = new DaoA();
        for (int k = 0; k < times; k++) {
            Dao y = x;
            y.find();
        }
    }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FACTORY FactoryConst FactoryConst.run DaoB.find "対照: 書き換えないクラス名の別名（String cur = name;）はファクトリの型に絞る" <<'EOF'
package pr;

public class FactoryConst {
    public static void main(String[] args) throws Exception { run(args.length); }
    static void run(int times) throws Exception {
        String name = "pr.DaoB";
        for (int k = 0; k < times; k++) {
            String cur = name;
            Dao d = Factory.create(cur);
            d.find();
        }
    }
}
EOF

# ---------------------------------------------------------------------------
# ローカル変数の値を書き換えるのは = だけではない（複合代入・++）
# ---------------------------------------------------------------------------
case_ reachable Incr Incr.count Incr.notEmpty "n++ で書き換わる n を 0 のままと見て n != 0 を打ち切らない" <<'EOF'
package pr;

public class Incr {
    public static void main(String[] args) { count(args); }
    static void count(String[] items) {
        int n = 0;
        for (String s : items) { n++; }
        if (n != 0) { notEmpty(); }
    }
    static void notEmpty() { System.out.println("n"); }
}
EOF

case_ reachable Compound Compound.calc Compound.two "m += 1 で書き換わる m を 1 のままと見て m == 2 を打ち切らない" <<'EOF'
package pr;

public class Compound {
    public static void main(String[] args) { calc(); }
    static void calc() {
        int m = 1;
        m += 1;
        if (m == 2) { two(); }
    }
    static void two() { System.out.println("2"); }
}
EOF

case_ pruned ConstLocal ConstLocal.calc ConstLocal.two "対照: 書き換えないローカル変数（int k = 1）は定数として打ち切る" <<'EOF'
package pr;

public class ConstLocal {
    public static void main(String[] args) { calc(); }
    static void calc() {
        int k = 1;
        if (k == 2) { two(); }
    }
    static void two() { System.out.println("2"); }
}
EOF

# ---------------------------------------------------------------------------
# 匿名・ローカルクラスのフィールド初期化子（囲むメソッドの引数を、この型のコンストラクタ引数と取り違えない）
# ---------------------------------------------------------------------------
case_ listed Leak 'Leak$1.go' DaoB.find "匿名クラスの inner = outer（囲むメソッドの引数）を super(new DaoA()) の引数と取り違えない" <<'EOF'
package pr;

public class Leak {
    public static void main(String[] args) { start(new DaoB()); }
    static void start(Dao outer) {
        Task t = new Task(new DaoA()) {
            private final Dao inner = outer;
            void go() { inner.find(); }
        };
        t.go();
    }
}
EOF

case_ listed LeakLocal 'LeakLocal$1Holder.go' DaoB.find "ローカルクラスでも同じ" <<'EOF'
package pr;

public class LeakLocal {
    public static void main(String[] args) { start(new DaoB()); }
    static void start(Dao outer) {
        class Holder extends Task {
            private final Dao inner = outer;
            Holder(Dao seed) { super(seed); }
            void go() { inner.find(); }
        }
        new Holder(new DaoA()).go();
    }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FIELD Capt 'Capt$1.go' DaoB.find "対照: 捕捉したローカル変数の new（型だけ）は、匿名クラスのフィールドでも使う" <<'EOF'
package pr;

public class Capt {
    public static void main(String[] args) { start(); }
    static void start() {
        Dao outer = new DaoB();
        Task t = new Task(new DaoA()) {
            private final Dao inner = outer;
            void go() { inner.find(); }
        };
        t.go();
    }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FIELD Inject Inject.run DaoB.find "対照: コンストラクタで受け取るフィールドは、渡された型に絞る" <<'EOF'
package pr;

public class Inject {
    private final Dao dao;
    Inject(Dao dao) { this.dao = dao; }
    public static void main(String[] args) { new Inject(new DaoB()).run(); }
    void run() { dao.find(); }
}
EOF

# ---------------------------------------------------------------------------
# new が代入される変数（LOCAL_NEW）。new 以外の値も入りうる変数では絞らない
# ---------------------------------------------------------------------------
# 絞り込みを落とさないことは、new した型と、それ以外から入る型の「両方」が出ることで見る。
# どちらも同じ経路で起こりうる（reset の値は解析では決まらない）ように書く。
# 片方だけを見ると、もう片方を落とす誤り（引数の出所を残して new を捨てる、など）を見逃す
case_ listed NewParam NewParam.use DaoB.find "引数 d に new を代入しても、呼び出し元が渡した実装を残す" <<'EOF'
package pr;

public class NewParam {
    public static void main(String[] args) { use(new DaoB(), args.length > 0); }
    static void use(Dao d, boolean reset) {
        if (reset) { d = new DaoA(); }
        d.find();
    }
}
EOF
expect_ listed NewParam.use DaoA.find "同上（代入した new DaoA も残す）"

case_ listed NewMixed NewMixed.mixed DaoB.find "new 以外（引数）も代入されるローカル変数では絞らない" <<'EOF'
package pr;

public class NewMixed {
    public static void main(String[] args) { mixed(new DaoB(), args.length > 0); }
    static void mixed(Dao given, boolean useGiven) {
        Dao d = new DaoA();
        if (useGiven) { d = given; }
        d.find();
    }
}
EOF
expect_ listed NewMixed.mixed DaoA.find "同上（初期化子の new DaoA も残す）"

case_ listed NewField NewField.lazy DaoB.find "フィールドに new を代入しても、ほかのメソッドが入れた実装を残す" <<'EOF'
package pr;

public class NewField {
    private Dao dao;
    public static void main(String[] args) {
        NewField f = new NewField();
        f.set(new DaoB());
        f.lazy(args.length > 0);
    }
    void set(Dao d) { this.dao = d; }
    void lazy(boolean reset) {
        if (reset || dao == null) { dao = new DaoA(); }
        dao.find();
    }
}
EOF
expect_ listed NewField.lazy DaoA.find "同上（代入した new DaoA も残す）"

case_ listed NewLoop NewLoop.loop DaoB.find "拡張 for の変数に new を代入しても、コレクションの要素を残す" <<'EOF'
package pr;

import java.util.List;

public class NewLoop {
    public static void main(String[] args) { loop(List.of(new DaoB()), args.length > 0); }
    static void loop(List<Dao> daos, boolean reset) {
        for (Dao x : daos) {
            if (reset) { x = new DaoA(); }
            x.find();
        }
    }
}
EOF
expect_ listed NewLoop.loop DaoA.find "同上（代入した new DaoA も残す）"

case_ listed NewLoopElem NewLoopElem.loop DaoB.find "要素の出所が分かる拡張 for の変数でも同じ（要素の DaoB を残す）" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class NewLoopElem {
    public static void main(String[] args) { loop(args.length > 0); }
    static void loop(boolean reset) {
        List<Dao> daos = new ArrayList<>();
        daos.add(new DaoB());
        for (Dao x : daos) {
            if (reset) { x = new DaoA(); }
            x.find();
        }
    }
}
EOF
expect_ listed NewLoopElem.loop DaoA.find "同上（代入した new DaoA も残す）"

case_ resolved:UNEXPANDED:LOCAL_NEW_MULTI NewCast NewCast.pick DaoB.find "キャストを挟んだ new（(Dao) new DaoB()）も new として数える（LOCAL_NEW_MULTI）" <<'EOF'
package pr;

public class NewCast {
    public static void main(String[] args) { pick(args.length); }
    static void pick(int n) {
        Dao m;
        if (n > 0) { m = new DaoA(); } else { m = (Dao) new DaoB(); }
        m.find();
    }
}
EOF
expect_ resolved:UNEXPANDED:LOCAL_NEW_MULTI NewCast.pick DaoA.find "同上（キャストの無い new DaoA）"

case_ resolved:RESOLVED:LOCAL_NEW NewCastOne NewCastOne.one DaoA.find "対照: キャストを挟んだ new だけが代入されるローカル変数は LOCAL_NEW に絞る" <<'EOF'
package pr;

public class NewCastOne {
    public static void main(String[] args) { one(); }
    static void one() {
        Dao d = (Dao) new DaoA();
        d.find();
    }
}
EOF

case_ resolved:RESOLVED:LOCAL_NEW NewOnly NewOnly.only DaoA.find "対照: new だけが代入されるローカル変数は LOCAL_NEW に絞る" <<'EOF'
package pr;

public class NewOnly {
    public static void main(String[] args) { only(); }
    static void only() {
        Dao d = new DaoA();
        d.find();
    }
}
EOF

# ---------------------------------------------------------------------------
# 条件の値は値グラフのノードで持ち、期待値は切り詰めない（キャッシュの G 行）
# ---------------------------------------------------------------------------
case_ reachable PipeEq PipeEq.check PipeEq.hit "期待値に | を含む equals（\"a|b\"）は、\"a|b\" を渡した経路で打ち切らない" <<'EOF'
package pr;

public class PipeEq {
    public static void main(String[] args) { check("a|b"); }
    static void check(String s) { if (s.equals("a|b")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable PipeConst PipeConst.run PipeConst.hit "| を含む文字列の定数どうしの equals（成立する）を打ち切らない" <<'EOF'
package pr;

public class PipeConst {
    static final String MODE = "a|b";
    public static void main(String[] args) { run(); }
    static void run() { if (MODE.equals("a|b")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable ConstCopyHit ConstCopyHit.run ConstCopyHit.hit "定数フィールドを写したローカル変数（int m = MODE;）で成立する条件を打ち切らない" <<'EOF'
package pr;

public class ConstCopyHit {
    static final int MODE = 3;
    public static void main(String[] args) { run(); }
    static void run() { int m = MODE; if (m == 3) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable ConstCopyRewrite ConstCopyRewrite.run ConstCopyRewrite.hit "定数フィールドを写した後で書き換わるローカル変数は定数とみなさない" <<'EOF'
package pr;

public class ConstCopyRewrite {
    static final int MODE = 3;
    public static void main(String[] args) { run(args.length > 0); }
    static void run(boolean b) {
        int m = MODE;
        if (b) { m = 4; }
        if (m == 4) { hit(); }
    }
    static void hit() { System.out.println("h"); }
}
EOF

case_ pruned ConstCopy ConstCopy.run ConstCopy.hit "対照: 定数フィールドを写しただけのローカル変数（int m = MODE; の m == 4）は定数として打ち切る" <<'EOF'
package pr;

public class ConstCopy {
    static final int MODE = 3;
    public static void main(String[] args) { run(); }
    static void run() { int m = MODE; if (m == 4) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ pruned PipeConstOther PipeConstOther.run PipeConstOther.hit "対照: | を含む文字列の定数と別の値の equals は打ち切る" <<'EOF'
package pr;

public class PipeConstOther {
    static final String MODE = "a|b";
    public static void main(String[] args) { run(); }
    static void run() { if (MODE.equals("zz")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable DefaultOnly DefaultOnly.sel DefaultOnly.hit "case の無い switch の default は、空文字を渡した経路でも打ち切らない（必ず通る）" <<'EOF'
package pr;

public class DefaultOnly {
    public static void main(String[] args) { sel(""); }
    static void sel(String s) {
        switch (s) {
            default -> hit();
        }
    }
    static void hit() { System.out.println("h"); }
}
EOF

# ---------------------------------------------------------------------------
# 戻り値の値（R 行）。読み手は「メソッドが返す値が 1 つに決まる」ときだけ、その値を経路の値として使う
# （docs/cache-unification-qa.md の Q8・Q9）
# ---------------------------------------------------------------------------
case_ reachable MixedRet MixedRet.run MixedRet.hit "Object を返すメソッドの return \"x\"（式の型は String）も戻り値に数える" <<'EOF'
package pr;

public class MixedRet {
    public static void main(String[] args) { run((String) key(args.length > 0)); }
    static Object key(boolean b) {
        if (b) { return "x"; }
        Object v = "y z";
        return v;
    }
    static void run(String s) { if (s.equals("x")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable MixedRetId MixedRetId.run MixedRetId.hit "同上。残りの return が識別子の形の文字列（\"yz\"）でも、\"x\" を返す経路を残す" <<'EOF'
package pr;

public class MixedRetId {
    public static void main(String[] args) { run((String) key(args.length > 0)); }
    static Object key(boolean b) {
        if (b) { return "x"; }
        Object v = "yz";
        return v;
    }
    static void run(String s) { if (s.equals("x")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable NePipeRet NePipeRet.run NePipeRet.hit "| を含む文字列を返すメソッドの値を | の手前（\"a\"）と読んで !s.equals(\"a\") を打ち切らない" <<'EOF'
package pr;

public class NePipeRet {
    public static void main(String[] args) { run((String) key()); }
    static Object key() { return (Object) "a|b"; }
    static void run(String s) { if (!s.equals("a")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ reachable PipeRetLocal PipeRetLocal.sel PipeRetLocal.other "| を含む文字列をローカル変数から返しても、switch の default（NI）を打ち切らない" <<'EOF'
package pr;

public class PipeRetLocal {
    public static void main(String[] args) { sel((String) key()); }
    static Object key() { Object v = "ORDER|DESC"; return v; }
    static void sel(String s) {
        switch (s) {
            case "ORDER" -> order();
            default -> other();
        }
    }
    static void order() { System.out.println("o"); }
    static void other() { System.out.println("x"); }
}
EOF

case_ listed FacPipeRet FacPipeRet.run DaoB.find "| を含むキー（\"A|B\"）を返すメソッドの値で、契約表の KeyFactory#get(\"A\") に当てない" <<'EOF'
package pr;

public class FacPipeRet {
    public static void main(String[] args) { run(); }
    static void run() { KeyFactory.get((String) key()).find(); }
    static Object key() { Object v = "A|B"; return v; }
}
EOF
expect_ listed FacPipeRet.run DaoA.find "同上（DaoA も残す）"

case_ pruned LitRet LitRet.run LitRet.hit "対照: Object を返すメソッドの return がどれも同じ識別子の形の文字列なら、その値で打ち切る" <<'EOF'
package pr;

public class LitRet {
    public static void main(String[] args) { run((String) key(args.length > 0)); }
    static Object key(boolean b) {
        if (b) { return "zz"; }
        Object v = "zz";
        return v;
    }
    static void run(String s) { if (s.equals("x")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ pruned LitRetLocal LitRetLocal.run LitRetLocal.hit "対照: ローカル変数から返す識別子の形の文字列も、その値で打ち切る" <<'EOF'
package pr;

public class LitRetLocal {
    public static void main(String[] args) { run((String) key()); }
    static Object key() { Object v = "zz"; return v; }
    static void run(String s) { if (s.equals("x")) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

case_ resolved:RESOLVED:CONTRACT FacKeyRet FacKeyRet.run DaoA.find "対照: 識別子の形のキー（\"A\"）を返すメソッドの値は、契約表の KeyFactory#get(\"A\") で絞る" <<'EOF'
package pr;

public class FacKeyRet {
    public static void main(String[] args) { run(); }
    static void run() { KeyFactory.get((String) key()).find(); }
    static Object key() { Object v = "A"; return v; }
}
EOF

# 返す文字列が以前の出所の文字列の文法の文字（| ;）を含んでも、値を切り詰めずにそのまま使う
# （以前の読み手は値を途中で切って読み違えるので、こういう戻り値を U（分からない）として渡していた。
# 読み手が値の表を読むようになって外した。docs/cache-unification-qa.md の Q9）
case_ reachable PipeRetEq PipeRetEq.run PipeRetEq.hit "| を含む文字列（\"a|b\"）を返すメソッドの値をそのまま読み、s.equals(\"a|b\") を打ち切らない" <<'EOF'
package pr;

public class PipeRetEq {
    public static void main(String[] args) { run((String) key()); }
    static Object key() { return (Object) "a|b"; }
    static void run(String s) {
        if (s.equals("a|b")) { hit(); }
        if (!s.equals("a|b")) { miss(); }
    }
    static void hit() { System.out.println("h"); }
    static void miss() { System.out.println("m"); }
}
EOF
expect_ pruned PipeRetEq.run PipeRetEq.miss "対照: 同じ値で !s.equals(\"a|b\") は打ち切る（返す値を U にせず、切り詰めずに読んでいる）"

case_ reachable PipeRetSwitch PipeRetSwitch.sel PipeRetSwitch.exact "| を含む文字列（\"ORDER|DESC\"）を返すメソッドの値で、switch の case \"ORDER|DESC\" を打ち切らない" <<'EOF'
package pr;

public class PipeRetSwitch {
    public static void main(String[] args) { sel((String) key()); }
    static Object key() { Object v = "ORDER|DESC"; return v; }
    static void sel(String s) {
        switch (s) {
            case "ORDER|DESC" -> exact();
            case "ORDER" -> order();
            default -> other();
        }
    }
    static void exact() { System.out.println("e"); }
    static void order() { System.out.println("o"); }
    static void other() { System.out.println("x"); }
}
EOF
expect_ pruned PipeRetSwitch.sel PipeRetSwitch.order "対照: case \"ORDER\" は打ち切る（| の手前で切って \"ORDER\" と読まない）"
expect_ pruned PipeRetSwitch.sel PipeRetSwitch.other "対照: default は打ち切る（値が case の 1 つと一致する）"

case_ resolved:RESOLVED:CONTRACT SemiRetKey SemiRetKey.run DaoB.find "; を含むキー（\"S;T\"）を返すメソッドの値で、契約表の SepFactory#get(\"S;T\") に当てる" <<'EOF'
package pr;

public class SemiRetKey {
    public static void main(String[] args) { run(); }
    static void run() { SepFactory.get((String) key()).find(); }
    static Object key() { Object v = "S;T"; return v; }
}
EOF
expect_ absent SemiRetKey.run DaoA.find "同上（; で切って get(\"S\") の DaoA に当てない）"

# ---------------------------------------------------------------------------
# 経路の値を読み違えない（stage B の B0。値の表へ移す前に文字列の側で直したもの）
# ---------------------------------------------------------------------------
case_ reachable /Kind /Kind.check /Kind.hitKind "無名パッケージの型 Kind（K で始まる）を経路の値（K: のクラス）と取り違えて、k.equals(\"x\") を空文字で打ち切らない" <<'EOF'
public class Kind {
    public static void main(String[] args) { check((String) make()); }
    static Object make() { return new Kind(); }
    static void check(String k) { if (k.equals("x")) { hitKind(); } }
    static void hitKind() { System.out.println("k"); }
}
EOF

case_ listed ReflField ReflField.run ReflField.take "getMethod の引数型の p.getClass()（p はコンストラクタで受け取った文字列）を、型でない値のままクラスとみなして呼び出しを落とさない" <<'EOF'
package pr;

public class ReflField {
    private final Object p;
    public ReflField(Object p) { this.p = p; }
    public void run(String name) throws Exception { ReflField.class.getMethod(name, p.getClass()).invoke(this, p); }
    public void take(String s) { System.out.println(s); }
    public static void main(String[] args) throws Exception { new ReflField("x").run("take"); }
}
EOF

# ---------------------------------------------------------------------------
# 値が以前の出所の文字列の文法の文字（| ; {）を含む。以前の読み手は値を出所の文字列に組み直してから
# 区切り文字で分けていたので、値を途中で切って読み違えていた。今の読み手は値の表（jche.graph.ValueStore）を
# 読み、値を切り詰めずに比べる（stage B）
# ---------------------------------------------------------------------------
case_ reachable SemiArg SemiArg.parse SemiArg.semicolon "実引数の \";\" を ; で切って空文字と読み、\";\".equals(delim) を打ち切らない" <<'EOF'
package pr;

public class SemiArg {
    public static void main(String[] args) { parse("x", ";"); }
    static void parse(String s, String delim) { if (";".equals(delim)) { semicolon(); } }
    static void semicolon() { System.out.println(";"); }
}
EOF

case_ pruned SemiArgOther SemiArgOther.parse SemiArgOther.semicolon "対照: 実引数が \",\" の経路では \";\".equals(delim) を打ち切る" <<'EOF'
package pr;

public class SemiArgOther {
    public static void main(String[] args) { parse("x", ","); }
    static void parse(String s, String delim) { if (";".equals(delim)) { semicolon(); } }
    static void semicolon() { System.out.println(";"); }
}
EOF

case_ reachable PipeArg PipeArg.parse2 PipeArg.notAb "実引数の \"a|c\" を | で切って \"a\" と読み、!mode.equals(\"a|b\") を打ち切らない" <<'EOF'
package pr;

public class PipeArg {
    public static void main(String[] args) { parse2("x", "a|c"); }
    static void parse2(String s, String mode) { if (!mode.equals("a|b")) { notAb(); } }
    static void notAb() { System.out.println("n"); }
}
EOF

case_ pruned PipeArgSame PipeArgSame.parse2 PipeArgSame.notAb "対照: 実引数が \"a|b\" の経路では !mode.equals(\"a|b\") を打ち切る" <<'EOF'
package pr;

public class PipeArgSame {
    public static void main(String[] args) { parse2("x", "a|b"); }
    static void parse2(String s, String mode) { if (!mode.equals("a|b")) { notAb(); } }
    static void notAb() { System.out.println("n"); }
}
EOF

case_ reachable BraceArg BraceArg.brace BraceArg.hitBrace "実引数の \"{\" から後ろを入れ子とみなして次の実引数まで飲み込み、\"{\".equals(first) を打ち切らない" <<'EOF'
package pr;

public class BraceArg {
    public static void main(String[] args) { brace("{", "k"); }
    static void brace(String first, String second) {
        if ("{".equals(first)) { hitBrace(); }
        if ("x".equals(second)) { braceMiss(); }
    }
    static void hitBrace() { System.out.println("{"); }
    static void braceMiss() { System.out.println("x"); }
}
EOF
expect_ pruned BraceArg.brace BraceArg.braceMiss "対照: 2 つ目の実引数（\"k\"）も読めるので \"x\".equals(second) は打ち切る（以前は飲み込まれて見えなかった）"

case_ resolved:RESOLVED:CONTRACT SepPipeKey SepPipeKey.run DaoB.find "ファクトリのキー \"USER|X\" は契約表の get(\"USER|X\") に当てる（| で切って get(\"USER\") に当てない）" <<'EOF'
package pr;

public class SepPipeKey {
    public static void main(String[] args) { run(); }
    static void run() { SepFactory.get("USER|X").find(); }
}
EOF
expect_ absent SepPipeKey.run DaoA.find "同上（get(\"USER\") の DaoA の行が無い）"

case_ resolved:RESOLVED:CONTRACT SepSemiKey SepSemiKey.run DaoB.find "ファクトリのキー \"S;T\" は契約表の get(\"S;T\") に当てる（; で切って get(\"S\") に当てない）" <<'EOF'
package pr;

public class SepSemiKey {
    public static void main(String[] args) { run(); }
    static void run() { SepFactory.get("S;T").find(); }
}
EOF
expect_ absent SepSemiKey.run DaoA.find "同上（get(\"S\") の DaoA の行が無い）"

case_ reachable HolderSemi HolderSemi.helper HolderSemi.hitHolder "new の実引数 \"a;r=K:x.Y\" を ; で切り、コンストラクタで受け取るフィールドの値を \"a\" と読んで打ち切らない" <<'EOF'
package pr;

public class HolderSemi {
    private final String v;
    public HolderSemi(String v) { this.v = v; }
    public void run() { helper(v); }
    static void helper(String s) { if (s.equals("a;r=K:x.Y")) { hitHolder(); } }
    static void hitHolder() { System.out.println("h"); }
    public static void main(String[] args) { new HolderSemi("a;r=K:x.Y").run(); }
}
EOF

# ---------------------------------------------------------------------------
# 戻り値の値（R 行）は、呼び出しがその宣言の本体でしか動かないときだけ使う（CallGraph#hasOverriders）。
# 部分型が上書きしていれば、実際に動くのは部分型の本体かもしれない。宣言の return の値を当てると、
# 呼ばれる呼び出しを [UNREACHABLE] にしたり、宣言の本体が返す型へ絞ったりして、上書きした本体の側を落とす。
# 条件分岐の値（literalOf）・契約表のキー（literalOf）・ファクトリの畳み込み（DataflowBuilder の reduce と
# 実引数の畳み込み、DataflowResolver の concreteSlotOf）・Class を返すメソッド（classOf）・@Bean の登録の
# どれで使っても同じ
# ---------------------------------------------------------------------------
case_ reachable OvrLit OvrLit.chk OvrLit.notAb "上書きされうるメソッド b.mode() の値を、宣言（OvrLitBase）の return \"a|b\" と読んで打ち切らない" <<'EOF'
package pr;

class OvrLitBase { public Object mode() { return "a|b"; } }
class OvrLitSub extends OvrLitBase { @Override public Object mode() { return "x"; } }

public class OvrLit {
    public static void main(String[] args) { o1(new OvrLitSub()); }
    static void o1(OvrLitBase b) { chk((String) b.mode()); }
    static void chk(String s) {
        if (!s.equals("a|b")) { notAb(); }
        if (s.equals("x")) { isX(); }
    }
    static void notAb() { System.out.println("n"); }
    static void isX() { System.out.println("x"); }
}
EOF
expect_ reachable OvrLit.chk OvrLit.isX "同上（上書きした本体が返す \"x\" の側の s.equals(\"x\") も打ち切らない）"

case_ listed OvrKey OvrKey.run DaoA.find "上書きされうるメソッド c.key() の値（宣言は \"USER|X\"）を契約表のキーにして、get(\"USER|X\") の DaoB に絞らない" <<'EOF'
package pr;

class OvrKeyCfg { public Object key() { return "USER|X"; } }
class OvrKeyProd extends OvrKeyCfg { @Override public Object key() { return "S"; } }

public class OvrKey {
    public static void main(String[] args) { run(); }
    static void run() {
        OvrKeyCfg c = new OvrKeyProd();
        SepFactory.get((String) c.key()).find();
    }
}
EOF
expect_ listed OvrKey.run DaoB.find "同上（DaoB も残す）"

case_ listed OvrFac OvrFac.run DaoB.find "上書きされうるファクトリ f.make() の値を、宣言の return new DaoA() と畳んで DaoA に絞らない" <<'EOF'
package pr;

class OvrFacBase { Dao make() { return new DaoA(); } }
class OvrFacSub extends OvrFacBase { @Override Dao make() { return new DaoB(); } }

public class OvrFac {
    public static void main(String[] args) { run(new OvrFacSub()); }
    static void run(OvrFacBase f) { f.make().find(); }
}
EOF
expect_ listed OvrFac.run DaoA.find "同上（DaoA も残す）"

case_ listed OvrFacDel OvrFacDel.run DaoB.find "委譲 return f.make(); を畳むときも、上書きされうる委譲先の宣言の return で決めない（DataflowBuilder の reduce）" <<'EOF'
package pr;

class OvrDelBase { Dao make() { return new DaoA(); } }
class OvrDelSub extends OvrDelBase { @Override Dao make() { return new DaoB(); } }

public class OvrFacDel {
    public static void main(String[] args) { run(new OvrDelSub()); }
    static Dao make2(OvrDelBase f) { return f.make(); }
    static void run(OvrDelBase f) { make2(f).find(); }
}
EOF
expect_ listed OvrFacDel.run DaoA.find "同上（DaoA も残す）"

case_ listed OvrFacArg OvrFacArg.run DaoB.find "実引数 id(f.make()) の f.make() を畳むときも、上書きされうる宣言の return で決めない（DataflowBuilder の実引数の畳み込み）" <<'EOF'
package pr;

class OvrArgBase { Dao make() { return new DaoA(); } }
class OvrArgSub extends OvrArgBase { @Override Dao make() { return new DaoB(); } }

public class OvrFacArg {
    public static void main(String[] args) { run(new OvrArgSub()); }
    static Dao id(Dao d) { return d; }
    static Dao wrap(OvrArgBase f) { return id(f.make()); }
    static void run(OvrArgBase f) { wrap(f).find(); }
}
EOF
expect_ listed OvrFacArg.run DaoA.find "同上（DaoA も残す）"

case_ listed OvrRef OvrRef.useU DaoB.find "束縛したレシーバの型が分からないメソッド参照 b::make の戻り値を、上書きされうる参照先の宣言の return で決めない" <<'EOF'
package pr;

import java.util.function.Supplier;

class OvrRefBase { Dao make() { return new DaoA(); } }
class OvrRefSub extends OvrRefBase { @Override Dao make() { return new DaoB(); } }

public class OvrRef {
    public static void main(String[] args) { viaUnknown(new OvrRefSub()); viaNew(); }
    static void viaUnknown(OvrRefBase b) { useU(b::make); }
    static void viaNew() { useN(new OvrRefSub()::make); }
    static void useU(Supplier<Dao> s) { s.get().find(); }
    static void useN(Supplier<Dao> s) { s.get().find(); }
}
EOF
expect_ listed OvrRef.useU DaoA.find "同上（DaoA も残す）"
expect_ resolved:RESOLVED:DATAFLOW_FACTORY OvrRef.useN DaoB.find "対照: 束縛したレシーバが new OvrRefSub() なら、その型で実際に動く本体（OvrRefSub.make）の return で絞る"
expect_ absent OvrRef.useN DaoA.find "同上（宣言の本体の DaoA の行が無い）"

case_ listed OvrCls OvrCls.run Method.invoke "上書きされうるメソッド b.type() が返す Class を、宣言の return OvrClsA.class と読んでリフレクションを OvrClsA.go に絞らない" <<'EOF'
package pr;

class OvrClsA { public void go() { System.out.println("a"); } }
class OvrClsB { public void go() { System.out.println("b"); } }
class OvrClsBase { Class<?> type() { return OvrClsA.class; } }
class OvrClsSub extends OvrClsBase { @Override Class<?> type() { return OvrClsB.class; } }

public class OvrCls {
    public static void main(String[] args) throws Exception { run(new OvrClsSub(), new OvrClsB()); }
    static void run(OvrClsBase b, Object target) throws Exception { b.type().getMethod("go").invoke(target); }
}
EOF
expect_ absent OvrCls.run OvrClsA.go "同上（OvrClsA.go だけに決めた行が無い）"

case_ listed SbOver SbOver.run SbDaoB.find "@Bean メソッドを部分型の設定クラスが上書きしていれば、上書きした本体が返す型（SbDaoB）も Bean に数え、段 5 で SbDaoA に絞らない" <<'EOF'
package pr;

interface SbDao { void find(); }
class SbDaoA implements SbDao { public void find() { System.out.println("a"); } }
class SbDaoB implements SbDao { public void find() { System.out.println("b"); } }
class SbCfg { @Bean SbDao dao() { return new SbDaoA(); } }
class SbCfgTest extends SbCfg { @Override SbDao dao() { return new SbDaoB(); } }

public class SbOver {
    @Autowired private SbDao dao;
    public static void main(String[] args) { new SbOver().run(); }
    void run() { dao.find(); }
}
EOF
expect_ listed SbOver.run SbDaoA.find "同上（SbDaoA も残す）"

# 対照: 上書きされえないメソッドは、これまでどおり return の値を使う（仕組みごと外して通すことを防ぐ）
case_ pruned=a\|b OvrStatic OvrStatic.chk OvrStatic.isX "対照: static メソッドは部分型が同じシグネチャを宣言しても隠蔽で上書きではないので、OvrStaticBase.mode() の値 \"a|b\" で打ち切る" <<'EOF'
package pr;

class OvrStaticBase { static Object mode() { return "a|b"; } }
class OvrStaticSub extends OvrStaticBase { static Object mode() { return "x"; } }

public class OvrStatic {
    public static void main(String[] args) { chk((String) OvrStaticBase.mode()); }
    static void chk(String s) { if (s.equals("x")) { isX(); } }
    static void isX() { System.out.println("x"); }
}
EOF

case_ pruned=a\|b OvrPriv OvrPriv.chk OvrPriv.isX "対照: private メソッドは部分型が同じシグネチャを宣言しても別のメソッドなので、mode() の値 \"a|b\" で打ち切る" <<'EOF'
package pr;

public class OvrPriv {
    private Object mode() { return "a|b"; }
    public static void main(String[] args) { new OvrPrivSub().run(); }
    void run() { chk((String) mode()); }
    static void chk(String s) { if (s.equals("x")) { isX(); } }
    static void isX() { System.out.println("x"); }
}

class OvrPrivSub extends OvrPriv {
    @SuppressWarnings("unused")
    private Object mode() { return "x"; }
}
EOF

case_ pruned=a\|b OvrFinal OvrFinal.chk OvrFinal.isX "対照: final メソッドは上書きされないので、b.mode() の値 \"a|b\" で打ち切る" <<'EOF'
package pr;

class OvrFinalBase { final Object mode() { return "a|b"; } }
class OvrFinalSub extends OvrFinalBase { }

public class OvrFinal {
    public static void main(String[] args) { run(new OvrFinalSub()); }
    static void run(OvrFinalBase b) { chk((String) b.mode()); }
    static void chk(String s) { if (s.equals("x")) { isX(); } }
    static void isX() { System.out.println("x"); }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FACTORY OvrInh OvrInh.run DaoB.find "対照: 部分型が上書きせずに継承しているだけなら、宣言の return new DaoB() で絞る" <<'EOF'
package pr;

class OvrInhBase { Dao make() { return new DaoB(); } }
class OvrInhSub extends OvrInhBase { }

public class OvrInh {
    public static void main(String[] args) { run(new OvrInhSub()); }
    static void run(OvrInhBase f) { f.make().find(); }
}
EOF
expect_ absent OvrInh.run DaoA.find "同上（DaoA の行が無い）"

case_ resolved:RESOLVED:SPRING_DI SbKeep SbKeep.run SkDaoA.find "対照: 上書きされない @Bean メソッドが返す型（SkDaoA）だけが Bean なら、段 5 で SkDaoA に絞る" <<'EOF'
package pr;

interface SkDao { void find(); }
class SkDaoA implements SkDao { public void find() { System.out.println("a"); } }
class SkDaoB implements SkDao { public void find() { System.out.println("b"); } }
class SkCfg { @Bean SkDao dao() { return new SkDaoA(); } }
class SkCfgSub extends SkCfg { }

public class SbKeep {
    @Autowired private SkDao dao;
    public static void main(String[] args) { new SbKeep().run(); }
    void run() { dao.find(); }
}
EOF
expect_ absent SbKeep.run SkDaoB.find "同上（SkDaoB の行が無い）"

# ---------------------------------------------------------------------------
# 値の表の読み手（stage B で値の表を読むようにした DataflowResolver・GuardEvaluator・StreamingTreeWalker・
# FactoryCalls・SpringBeans）の分かれ道。どれも 1 行の取り違えで、呼び出しを黙って落とすか、
# 違う先へ繋ぐか、打ち切りが効かなくなる。移し替えの間だけ置いた経路の記録（test/dataflow の TraceCheck）を消したあとは、
# この節と下の「洗い出した穴」の節が読み手の分かれ道を守る。1 行ずつ壊す変異 96 個のうち、このスクリプトが 66 個、
# test/regression が残りの 16 個を検出する（TraceCheck があったときは 80 個）。検出しない 14 個とその理由
# （同値・保守側に倒れるだけ・検出できていない 3 個）は docs/cache-unification-qa.md の Q16
# ---------------------------------------------------------------------------
case_ resolved:UNEXPANDED:REFLECTION ReflNullArg ReflNullArg.main ReflNullArg.take "getMethod の引数型に値の分からない実引数（null）があれば、引数の数（n=）まで見て型は分からないとし、名前の一致する take() と take(String) の両方を候補に残す" <<'EOF'
package pr;

public class ReflNullArg {
    public void take() { a(); }
    public void take(String s) { b(); }
    static void a() { System.out.println("a"); }
    static void b() { System.out.println("b"); }
    public static void main(String[] args) throws Exception {
        ReflNullArg.class.getMethod("take", (Class<?>) null).invoke(new ReflNullArg(), "x");
    }
}
EOF

case_ from:ReflCtor.main ReflCtor ReflCtor.'<init>' ReflCtor.withArg "getConstructor(String.class) の引数型は第 1 引数から数え、newInstance(\"x\") を <init>(String) に繋ぐ（<init>() に繋がない）" <<'EOF'
package pr;

public class ReflCtor {
    public ReflCtor() { noArg(); }
    public ReflCtor(String s) { withArg(); }
    static void noArg() { System.out.println("0"); }
    static void withArg() { System.out.println("1"); }
    public static void main(String[] args) throws Exception {
        ReflCtor.class.getConstructor(String.class).newInstance("x");
    }
}
EOF

case_ pruned=\; SparseArg SparseArg.parse SparseArg.miss "値の分からない実引数（null）の後ろの実引数 \";\" を、実引数の位置（2 番目）の引数に当てる（詰めて 1 番目に当てない）" <<'EOF'
package pr;

public class SparseArg {
    public static void main(String[] args) { parse(null, ";"); }
    static void parse(String s, String d) {
        if (d.equals("x")) { miss(); }
        if (!";".equals(s)) { hitS(); }
    }
    static void miss() { System.out.println("m"); }
    static void hitS() { System.out.println("s"); }
}
EOF
expect_ reachable SparseArg.parse SparseArg.hitS "同上（1 番目の引数 s は null で値が分からないので、!\";\".equals(s) を打ち切らない）"

case_ resolved:UNEXPANDED:LAMBDA FieldTask FieldTask.go Runnable.run "フィールドの値が関数型の値（Z）でないとき（makeTask() の戻り値）は、そのフィールドのラムダとして繋がない" <<'EOF'
package pr;

public class FieldTask {
    private final Runnable task = makeTask();
    static Runnable makeTask() { return () -> System.out.println("x"); }
    void go() { task.run(); }
    public static void main(String[] args) { new FieldTask().go(); }
}
EOF
expect_ absent FieldTask.go FieldTask.makeTask "同上（task.run() を makeTask へ繋いだ行が無い）"

case_ listed ClsRet ClsRet.main Method.invoke "Class を返すメソッドの return が 2 通り（ClsRet.class と ClsRetOther.class）なら、先頭の return だけでリフレクションを決めない" <<'EOF'
package pr;

class ClsRetOther { public void take(String s) { System.out.println("o"); } }

public class ClsRet {
    public void take(String s) { System.out.println("c"); }
    static Class<?> owner(boolean b) {
        if (b) { return ClsRet.class; }
        return ClsRetOther.class;
    }
    public static void main(String[] args) throws Exception {
        owner(args.length > 0).getMethod("take", String.class).invoke(new ClsRet(), "x");
    }
}
EOF
expect_ absent ClsRet.main ClsRet.take "同上（ClsRet.take だけに決めた行が無い）"

case_ pruned=a\;b HolderPass HolderPass.helper HolderPass.miss "対照: コンストラクタで受け取るフィールドは、経路の実引数の値（\"a;b\"）をそのまま次の呼び出しへ運び、s.equals(\"zz\") を打ち切る" <<'EOF'
package pr;

public class HolderPass {
    private final String v;
    HolderPass(String v) { this.v = v; }
    public static void main(String[] args) { new HolderPass("a;b").run(); }
    void run() { helper(v); }
    static void helper(String s) { if (s.equals("zz")) { miss(); } }
    static void miss() { System.out.println("m"); }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FIELD InjParam InjParam.run DaoB.find "対照: new InjParam(d) の実引数 d（呼び出し元の引数）は呼び出し元の経路で解き、コンストラクタで受け取るフィールドを DaoB に絞る" <<'EOF'
package pr;

public class InjParam {
    private final Dao dao;
    InjParam(Dao dao) { this.dao = dao; }
    public static void main(String[] args) { make(new DaoB()); }
    static void make(Dao d) { new InjParam(d).run(); }
    void run() { dao.find(); }
}
EOF
expect_ absent InjParam.run DaoA.find "同上（DaoA の行が無い）"

case_ resolved:RESOLVED:DATAFLOW_FACTORY IdFac IdFac.run DaoB.find "対照: 引数をそのまま返すメソッド id(d) の d は呼び出し元の経路で解き、DaoB に絞る" <<'EOF'
package pr;

public class IdFac {
    static Dao id(Dao x) { return x; }
    public static void main(String[] args) { run(new DaoB()); }
    static void run(Dao d) { id(d).find(); }
}
EOF
expect_ absent IdFac.run DaoA.find "同上（DaoA の行が無い）"

case_ pruned=q MultiAtom MultiAtom.run MultiAtom.miss "対照: && の条件に値の分からない項（s）があっても、残りの項（t.equals(\"zz\")）で打ち切る" <<'EOF'
package pr;

public class MultiAtom {
    public static void main(String[] args) { run(String.valueOf(args.length), "q"); }
    static void run(String s, String t) { if (s.equals("a") && t.equals("zz")) { miss(); } }
    static void miss() { System.out.println("m"); }
}
EOF

case_ listed EnumKey EnumKey.run DaoB.find "引数で渡った列挙定数（EkMode.X）を文字列のキー \"pr.EkMode.X\" と取り違えて、契約表の get(\"pr.EkMode.X\") の DaoA に絞らない" <<'EOF'
package pr;

enum EkMode { X, Y }

class EnumKeyFac {
    static Dao get(Object key) { return (key.hashCode() > 0) ? new DaoA() : new DaoB(); }
}

public class EnumKey {
    public static void main(String[] args) { run(EkMode.X); }
    static void run(EkMode m) { EnumKeyFac.get(m).find(); }
}
EOF
expect_ listed EnumKey.run DaoA.find "同上（DaoA も残す）"

case_ listed SbName SbName.run SnDaoA.find "@Bean メソッドが返す Class の値（SnDaoA.class）を Bean の型と取り違えず（new の値だけを登録する）、段 5 で SnDaoA に絞らない" <<'EOF'
package pr;

interface SnDao { void find(); }
class SnDaoA implements SnDao { public void find() { System.out.println("a"); } }
class SnDaoB implements SnDao { public void find() { System.out.println("b"); } }
class SnCfg { @Bean Class<?> daoType() { return SnDaoA.class; } }

public class SbName {
    @Autowired private SnDao dao;
    public static void main(String[] args) { new SbName().run(); }
    void run() { dao.find(); }
}
EOF
expect_ listed SbName.run SnDaoB.find "同上（SnDaoB も残す）"

# ---------------------------------------------------------------------------
# 経路の記録（TraceCheck）を消すときに、読み手の分かれ道を 1 行ずつ壊す変異で洗い出した穴
# （DataflowResolver・DataflowBuilder・GuardEvaluator・StreamingTreeWalker・FactoryCalls・CallbackContracts・
# FieldFacts・SpringBeans）。TraceCheck だけが検出していた取り違え（リフレクションの受け手の具象型・引数で渡った
# Class・契約表の実引数の位置・呼び戻しの 2 番目以降の実引数・this の呼び出しと継承したメソッドへのコンストラクタ
# 実引数・親のフィールドの持ち主・static や値の食い違うフィールドをコンストラクタ注入とみなさないこと・
# フィールドを実引数にしたファクトリ・複数の値の case）と、どの検査も検出していなかった取り違え（引数で渡った
# メソッド参照・return が 2 通りの @Bean・private でも final でもないフィールド・setter でも代入するフィールド）を
# 1 つずつ押さえる（docs/cache-unification-qa.md の Q16）
# ---------------------------------------------------------------------------
case_ resolved:RESOLVED:REFLECTION ReflRecv ReflRecv.run ReflRecvSub.f "invoke の第 1 引数（new ReflRecvSub()）の具象型で getMethod の先の上書きを引き、ReflRecvSub.f に繋ぐ（宣言の ReflRecv.f に決めない）" <<'EOF'
package pr;

import java.lang.reflect.Method;

class ReflRecvSub extends ReflRecv {
    @Override public void f() { System.out.println("s"); }
}

public class ReflRecv {
    public void f() { System.out.println("b"); }
    public static void run() throws Exception {
        Method m = ReflRecv.class.getMethod("f");
        m.invoke(new ReflRecvSub());
    }
}
EOF
expect_ absent ReflRecv.run ReflRecv.f "同上（宣言の ReflRecv.f に繋いだ行が無い）"

case_ from:ClsParam.main ClsParam ClsParamT.'<init>' ClsParamT.hit "引数で渡った Class（ClsParamT.class）から c.getDeclaredConstructor().newInstance() をそのコンストラクタに繋ぐ（コンストラクタが起点に回らない）" <<'EOF'
package pr;

class ClsParamT {
    ClsParamT() { hit(); }
    static void hit() { System.out.println("t"); }
}

public class ClsParam {
    static void make(Class<?> c) throws Exception { c.getDeclaredConstructor().newInstance(); }
    public static void main(String[] args) throws Exception { make(ClsParamT.class); }
}
EOF

case_ pruned=c InSwitch InSwitch.check InSwitch.hit "case \"a\", \"b\"（IN）は、経路の値 \"c\" がどれとも一致しないので打ち切る" <<'EOF'
package pr;

public class InSwitch {
    public static void main(String[] args) { check("c"); }
    static void check(String k) {
        switch (k) {
            case "a", "b" -> hit();
            default -> other();
        }
    }
    static void hit() { System.out.println("h"); }
    static void other() { System.out.println("o"); }
}
EOF
expect_ reachable InSwitch.check InSwitch.other "同上（default は打ち切らない）"

case_ resolved:RESOLVED:CONTRACT TwoKeyFirst TwoKeyFirst.run DaoA.find "2 つのキー get(\"A\", \"B\") は実引数の先頭から引き、get(\"A\") の DaoA に絞る（位置の順を逆にしない）" <<'EOF'
package pr;

public class TwoKeyFirst {
    static void run() { TwoKeyFactory.get("A", "B").find(); }
}
EOF
expect_ absent TwoKeyFirst.run DaoB.find "同上（get(\"B\") の DaoB に当てない）"

case_ resolved:RESOLVED:CONTRACT TwoKeySecond TwoKeySecond.run DaoB.find "2 番目の実引数だけが表に載るキー get(\"X\", \"B\") も、その位置の実引数を読んで DaoB に絞る" <<'EOF'
package pr;

public class TwoKeySecond {
    static void run() { TwoKeyFactory.get("X", "B").find(); }
}
EOF
expect_ absent TwoKeySecond.run DaoA.find "同上（DaoA の行が無い）"

case_ resolved:RESOLVED:DATAFLOW_FIELD ThisInj ThisInj.use DaoB.find "コンストラクタ実引数（new ThisInj(new DaoB())）は、同じオブジェクトへの this の呼び出し（run → use）にも引き継ぎ、フィールド d を DaoB に絞る" <<'EOF'
package pr;

public class ThisInj {
    private final Dao d;
    ThisInj(Dao d) { this.d = d; }
    public static void main(String[] args) { new ThisInj(new DaoB()).run(); }
    void run() { use(); }
    void use() { d.find(); }
}
EOF
expect_ absent ThisInj.use DaoA.find "同上（DaoA の行が無い）"

case_ listed BaseInj BaseInj.go DaoB.find "new SubInj(new DaoA(), new DaoB()).go() の go は親 BaseInj の宣言。SubInj のコンストラクタ実引数を BaseInj の引数の位置で読んで d を DaoA に絞らない（実際は super(y) の DaoB）" <<'EOF'
package pr;

class SubInj extends BaseInj {
    SubInj(Dao x, Dao y) { super(y); }
}

public class BaseInj {
    private final Dao d;
    BaseInj(Dao d) { this.d = d; }
    void go() { d.find(); }
    public static void main(String[] args) { new SubInj(new DaoA(), new DaoB()).go(); }
}
EOF

case_ listed FfStatic FfStatic.use DaoB.find "static フィールドは生成ごとに上書きされるのでコンストラクタ注入とみなさない。new FfStatic(new DaoA()) の経路でも last を DaoA に絞らない（その間の new FfStatic(new DaoB()) が入れ替える）" <<'EOF'
package pr;

public class FfStatic {
    private static Dao last;
    FfStatic(Dao x) { last = x; }
    void run() { new FfStatic(new DaoB()); use(); }
    void use() { last.find(); }
    public static void main(String[] args) { new FfStatic(new DaoA()).run(); }
}
EOF
expect_ listed FfStatic.use DaoA.find "同上（DaoA も残す）"

case_ listed FfPkg FfPkg.use DaoB.find "private でも final でもないフィールドはクラスの外から代入されうる（FfPkgOther.poke）ので、コンストラクタ実引数の DaoA に絞らない" <<'EOF'
package pr;

class FfPkgOther { static void poke(FfPkg p) { p.d = new DaoB(); } }

public class FfPkg {
    Dao d;
    FfPkg(Dao x) { d = x; }
    void run() { FfPkgOther.poke(this); use(); }
    void use() { d.find(); }
    public static void main(String[] args) { new FfPkg(new DaoA()).run(); }
}
EOF
expect_ listed FfPkg.use DaoA.find "同上（DaoA も残す）"

case_ listed FfSet FfSet.use DaoB.find "コンストラクタの外（setter）でも代入されるフィールドは、コンストラクタ実引数の DaoA に絞らない（set(new DaoB()) の後に use）" <<'EOF'
package pr;

public class FfSet {
    private Dao d;
    FfSet(Dao x) { d = x; }
    void set(Dao x) { d = x; }
    void run() { set(new DaoB()); use(); }
    void use() { d.find(); }
    public static void main(String[] args) { new FfSet(new DaoA()).run(); }
}
EOF
expect_ listed FfSet.use DaoA.find "同上（DaoA も残す）"

case_ listed FfTwo FfTwo.use DaoA.find "コンストラクタごとに違う値を入れるフィールド（new DaoA() と new DaoB()）は、どちらか一方に絞らない" <<'EOF'
package pr;

public class FfTwo {
    private final Dao d;
    FfTwo() { d = new DaoA(); }
    FfTwo(int k) { d = new DaoB(); }
    void use() { d.find(); }
    public static void main(String[] args) { new FfTwo().use(); new FfTwo(1).use(); }
}
EOF
expect_ listed FfTwo.use DaoB.find "同上（DaoB も残す）"

case_ listed ReflNone ReflNone.run DaoA.find "解析対象に無いクラス名（\"pr.NoSuchDao\"）を Class.forName に渡すファクトリへの委譲（none() → Factory.create）は、その名前の型に畳まない（CHA の候補を残す）" <<'EOF'
package pr;

public class ReflNone {
    static Dao none() throws Exception { return Factory.create("pr.NoSuchDao"); }
    static void run() throws Exception { none().find(); }
}
EOF
expect_ listed ReflNone.run DaoB.find "同上（DaoB も残す）"

case_ resolved:RESOLVED:DATAFLOW_FACTORY FieldArgFac FieldArgFac.run DaoB.find "引数をそのまま返すメソッドにフィールド（private final Dao f = new DaoB()）を渡すファクトリ pick() を、フィールドの型 DaoB に畳む" <<'EOF'
package pr;

public class FieldArgFac {
    private final Dao f = new DaoB();
    static Dao id(Dao d) { return d; }
    Dao pick() { return id(f); }
    static void run() { new FieldArgFac().pick().find(); }
}
EOF
expect_ absent FieldArgFac.run DaoA.find "同上（DaoA の行が無い）"

case_ listed SbTwo SbTwo.run SbTwoA.find "@Bean メソッドの return が 2 つの型（new SbTwoA() と new SbTwoB()）なら Bean を登録せず、段 5 で後の return の型に絞らない" <<'EOF'
package pr;

interface SbTwoDao { void find(); }
class SbTwoA implements SbTwoDao { public void find() { System.out.println("a"); } }
class SbTwoB implements SbTwoDao { public void find() { System.out.println("b"); } }
class SbTwoCfg { @Bean SbTwoDao dao(boolean b) { if (b) { return new SbTwoA(); } return new SbTwoB(); } }

public class SbTwo {
    @Autowired private SbTwoDao dao;
    public static void main(String[] args) { new SbTwo().run(); }
    void run() { dao.find(); }
}
EOF
expect_ listed SbTwo.run SbTwoB.find "同上（SbTwoB も残す）"

# 返す具象型が値から決まらない @Bean メソッド（ファクトリ・引数・フィールド・条件演算子）。@Repository の SbXxA が
# ステレオタイプの Bean で、SbXxB は @Bean メソッドが返す。@Bean の Bean を数えないと、段 5 が SbXxA だけに絞って
# SbXxB.find を黙って落とす（docs/value-safety-qa.md の Q17）
case_ listed SbFac SbFac.run SbFacB.find "@Bean メソッドがファクトリの戻り値を返す（return SbFacB.make();）なら、その Bean（宣言した型 SbFacI の何か）を数え、段 5 で @Repository の SbFacA に絞らない" <<'EOF'
package pr;

interface SbFacI { void find(); }
@Repository class SbFacA implements SbFacI { public void find() { System.out.println("a"); } }
class SbFacB implements SbFacI {
    static SbFacB make() { return new SbFacB(); }
    public void find() { System.out.println("b"); }
}
class SbFacCfg { @Bean SbFacI b() { return SbFacB.make(); } }

public class SbFac {
    @Autowired private SbFacI r;
    public static void main(String[] args) { new SbFac().run(); }
    void run() { r.find(); }
}
EOF
expect_ listed SbFac.run SbFacA.find "同上（SbFacA も残す）"

case_ listed SbPar SbPar.run SbParB.find "@Bean メソッドが引数を返す（@Bean SbParI b(SbParB p) { return p; }）なら、段 5 で SbParA に絞らない" <<'EOF'
package pr;

interface SbParI { void find(); }
@Repository class SbParA implements SbParI { public void find() { System.out.println("a"); } }
class SbParB implements SbParI { public void find() { System.out.println("b"); } }
class SbParCfg { @Bean SbParI b(SbParB p) { return p; } }

public class SbPar {
    @Autowired private SbParI r;
    public static void main(String[] args) { new SbPar().run(); }
    void run() { r.find(); }
}
EOF
expect_ listed SbPar.run SbParA.find "同上（SbParA も残す）"

case_ listed SbFld SbFld.run SbFldB.find "@Bean メソッドがフィールドを返す（return f; f は new SbFldB()）なら、段 5 で SbFldA に絞らない" <<'EOF'
package pr;

interface SbFldI { void find(); }
@Repository class SbFldA implements SbFldI { public void find() { System.out.println("a"); } }
class SbFldB implements SbFldI { public void find() { System.out.println("b"); } }
class SbFldCfg {
    private final SbFldB f = new SbFldB();
    @Bean SbFldI b() { return f; }
}

public class SbFld {
    @Autowired private SbFldI r;
    public static void main(String[] args) { new SbFld().run(); }
    void run() { r.find(); }
}
EOF
expect_ listed SbFld.run SbFldA.find "同上（SbFldA も残す）"

case_ listed SbCond SbCond.run SbCondB.find "@Bean メソッドが条件演算子を返す（return c ? new SbCondB() : new SbCondB();）なら、段 5 で SbCondA に絞らない" <<'EOF'
package pr;

interface SbCondI { void find(); }
@Repository class SbCondA implements SbCondI { public void find() { System.out.println("a"); } }
class SbCondB implements SbCondI { public void find() { System.out.println("b"); } }
class SbCondCfg { @Bean SbCondI b(boolean c) { return c ? new SbCondB() : new SbCondB(); } }

public class SbCond {
    @Autowired private SbCondI r;
    public static void main(String[] args) { new SbCond().run(); }
    void run() { r.find(); }
}
EOF
expect_ listed SbCond.run SbCondA.find "同上（SbCondA も残す）"

case_ resolved:RESOLVED:SPRING_DI SbUnrel SbUnrel.run SbUnA.find "対照: 返す具象型の決まらない @Bean メソッドがあっても、宣言した型（SbUnOther）が候補の型に触れなければ、段 5 で @Repository の SbUnA に絞る" <<'EOF'
package pr;

interface SbUnI { void find(); }
@Repository class SbUnA implements SbUnI { public void find() { System.out.println("a"); } }
class SbUnB implements SbUnI { public void find() { System.out.println("b"); } }
class SbUnOther { static SbUnOther make() { return new SbUnOther(); } }
class SbUnCfg { @Bean SbUnOther other() { return SbUnOther.make(); } }

public class SbUnrel {
    @Autowired private SbUnI r;
    public static void main(String[] args) { new SbUnrel().run(); }
    void run() { r.find(); }
}
EOF
expect_ absent SbUnrel.run SbUnB.find "同上（SbUnB の行が無い）"

# 対になっていないサロゲートを含む文字列リテラル（"\uD800x"）。キャッシュは UTF-8 で書くので、符号化しないと
# 書けずに解析ごと失敗していた。値は \uXXXX として符号化し、読み戻すと元の char に戻る（別のサロゲートの値とは
# 等しくならない。置換文字に潰すと両方が同じになって、成立しない条件まで通してしまう）。docs/cache-unification-qa.md の Q49
case_ reachable SurLit SurLit.run SurLit.hit "対になっていないサロゲートの文字列（\"\\uD800x\"）の定数を渡した経路で、同じ文字列との equals を打ち切らない" <<'EOF'
package pr;

public class SurLit {
    static final String K = "\uD800x";
    public static void main(String[] args) { run(K); other("\uDBFFx"); }
    static void run(String k) { if ("\uD800x".equals(k)) { hit(); } }
    static void other(String k) { if ("\uD800x".equals(k)) { miss(); } }
    static void hit() { System.out.println("h"); }
    static void miss() { System.out.println("m"); }
}
EOF
expect_ pruned SurLit.other SurLit.miss "対照: 別のサロゲート（\"\\uDBFFx\"）を渡した経路では打ち切る（値を置換文字に潰していない）"

# 注記の条件式は 60 文字で切る。絵文字（サロゲートペア）がちょうど切れ目にかかっても、ペアの途中で切らない
# （条件式の文字列はソースの書き方のままなので、絵文字はエスケープせずにそのまま書く。s.equals(" の 10 文字と
# A の 49 文字の次、60 文字目が絵文字の上位サロゲート）
case_ pruned EmojiCut EmojiCut.run EmojiCut.target "60 文字目で切れる絵文字を含む条件でも解析でき、打ち切る（条件式の文字列をサロゲートペアの途中で切らない）" <<'EOF'
package pr;

public class EmojiCut {
    public static void main(String[] args) { run("zz"); }
    static void run(String s) { if (s.equals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA😀")) { target(); } }
    static void target() { System.out.println("t"); }
}
EOF

case_ listed CbParam CbParam.each DaoA.find "引数で渡ったメソッド参照（Dao::find。参照先はソースにある）を forEach の呼び戻し先にし、実装が 2 つでも落とさない" <<'EOF'
package pr;

import java.util.List;
import java.util.function.Consumer;

public class CbParam {
    static void each(List<Dao> l, Consumer<Dao> c) { l.forEach(c); }
    public static void main(String[] args) { each(List.of(new DaoA(), new DaoB()), Dao::find); }
}
EOF
expect_ listed CbParam.each DaoB.find "同上（DaoB も残す）"

case_ listed OwnField OwnChild.go DaoB.find "親 OwnParent のフィールド d（コンストラクタ引数 0 番）に、子 OwnChild のコンストラクタ実引数の 0 番（DaoA）を当てて絞らない（実際は super(y) の DaoB）" <<'EOF'
package pr;

class OwnParent {
    final Dao d;
    OwnParent(Dao d) { this.d = d; }
}

class OwnChild extends OwnParent {
    private final String tag;
    OwnChild(Dao x, Dao y) { super(y); tag = "t"; }
    void go() { d.find(); }
}

public class OwnField {
    public static void main(String[] args) { new OwnChild(new DaoA(), new DaoB()).go(); }
}
EOF

case_ resolved:RESOLVED:CALLBACK ThreadArg2 ThreadArg2.main 'ThreadArg2.lambda$main$0' "Thread の 2 番目のコンストラクタ実引数のラムダ（new Thread(group, () -> hit())）も start() の呼び戻し先にする" <<'EOF'
package pr;

public class ThreadArg2 {
    static void hit() { System.out.println("t"); }
    public static void main(String[] args) {
        new Thread(new ThreadGroup("g"), () -> hit()).start();
    }
}
EOF

# ---------------------------------------------------------------------------
# フィールドへの書き込みを 1 つでも取りこぼすと、読み手（FieldFacts）はコンストラクタで入れた値だけが入ると
# 言い切り、フィールドのレシーバをその型に絞ったり、フィールドの値で条件を打ち切ったりする。
# 入れ子のクラス・static な入れ子のクラス・++ と複合代入・初期化ブロック・初期化子のラムダからの書き込み、
# 条件の中や return の後ろのコンストラクタの書き込み（通らない経路では既定値 0 / false / null が残る）、
# 書き換えた引数を入れる代入（docs/value-safety-qa.md の Q18）
# ---------------------------------------------------------------------------
case_ listed FfInner FfInner.go DaoB.find "内部クラス（Setter）がフィールド dao に書くなら、コンストラクタ実引数の DaoA に絞らない" <<'EOF'
package pr;

public class FfInner {
    private Dao dao;
    FfInner(Dao d) { this.dao = d; }
    class Setter { void set(Dao d) { dao = d; } }
    void go() { dao.find(); }
    public static void main(String[] args) {
        FfInner f = new FfInner(new DaoA());
        f.new Setter().set(new DaoB());
        f.go();
    }
}
EOF
expect_ listed FfInner.go DaoA.find "同上（DaoA も残す）"

case_ listed FfNested FfNested.go DaoB.find "static な入れ子のクラス（Mut.set）が f.dao に書くなら、コンストラクタ実引数の DaoA に絞らない" <<'EOF'
package pr;

public class FfNested {
    private Dao dao;
    FfNested(Dao d) { this.dao = d; }
    static class Mut { static void set(FfNested f, Dao d) { f.dao = d; } }
    void go() { dao.find(); }
    public static void main(String[] args) {
        FfNested f = new FfNested(new DaoA());
        Mut.set(f, new DaoB());
        f.go();
    }
}
EOF

case_ reachable FfIncr FfIncr.check FfIncr.hit "n++ で書き換えるフィールドは、コンストラクタ実引数（0）のままとみなして x == 1 を打ち切らない" <<'EOF'
package pr;

public class FfIncr {
    private int n;
    FfIncr(int n) { this.n = n; }
    void inc() { n++; }
    void go() { check(n); }
    void check(int x) { if (x == 1) { hit(); } }
    void hit() { System.out.println("h"); }
    public static void main(String[] args) { FfIncr f = new FfIncr(0); f.inc(); f.go(); }
}
EOF

case_ reachable FfCompound FfCompound.check FfCompound.hit "コンストラクタの this.m += n は右辺（n）の値を入れるのではない。m を n の値 1 とみなして x == 2 を打ち切らない" <<'EOF'
package pr;

public class FfCompound {
    private int m;
    FfCompound(int n) { this.m = n; this.m += n; }
    void go() { check(m); }
    void check(int x) { if (x == 2) { hit(); } }
    void hit() { System.out.println("h"); }
    public static void main(String[] args) { new FfCompound(1).go(); }
}
EOF

case_ listed FfBlock FfBlock.go DaoB.find "初期化ブロック（{ dao = new DaoB(); }）とコンストラクタの条件の中の書き込みがあれば、コンストラクタ実引数の DaoA に絞らない" <<'EOF'
package pr;

public class FfBlock {
    private Dao dao;
    { dao = new DaoB(); }
    FfBlock(Dao d, boolean use) { if (use) { this.dao = d; } }
    void go() { dao.find(); }
    public static void main(String[] args) { new FfBlock(new DaoA(), false).go(); }
}
EOF

case_ reachable FfCond FfCond.check FfCond.hit "コンストラクタの条件の中でだけ書くフィールドは、通らない経路で既定値 0 のまま。x == 0 を打ち切らない" <<'EOF'
package pr;

public class FfCond {
    private int mode;
    FfCond(int m, boolean use) { if (use) { this.mode = m; } }
    void go() { check(mode); }
    void check(int x) { if (x == 0) { hit(); } }
    void hit() { System.out.println("h"); }
    public static void main(String[] args) { new FfCond(1, false).go(); }
}
EOF

case_ reachable FfReturn FfReturn.check FfReturn.hit "return の後ろのコンストラクタの書き込みも、return で抜ける経路では既定値 false のまま。!on を打ち切らない" <<'EOF'
package pr;

public class FfReturn {
    private boolean on;
    FfReturn(boolean v, boolean skip) {
        if (skip) { return; }
        this.on = v;
    }
    void go() { check(on); }
    void check(boolean x) { if (!x) { hit(); } }
    void hit() { System.out.println("h"); }
    public static void main(String[] args) { new FfReturn(true, true).go(); }
}
EOF

case_ listed FfReParam FfReParam.go DaoB.find "コンストラクタの中で書き換えた引数（d = new DaoB();）を入れるフィールドは、渡された DaoA に絞らない" <<'EOF'
package pr;

public class FfReParam {
    private final Dao dao;
    FfReParam(Dao d, boolean swap) {
        if (swap) { d = new DaoB(); }
        this.dao = d;
    }
    void go() { dao.find(); }
    public static void main(String[] args) { new FfReParam(new DaoA(), true).go(); }
}
EOF

case_ reachable FfReInt FfReInt.check FfReInt.hit "m = m + 1; this.mode = m; の mode は渡された 1 ではない。x == 2 を打ち切らない" <<'EOF'
package pr;

public class FfReInt {
    private final int mode;
    FfReInt(int m) { m = m + 1; this.mode = m; }
    void go() { check(mode); }
    void check(int x) { if (x == 2) { hit(); } }
    void hit() { System.out.println("h"); }
    public static void main(String[] args) { new FfReInt(1).go(); }
}
EOF

case_ listed FfLambdaInit FfLambdaInit.go DaoB.find "フィールド初期化子のラムダ（setter = x -> this.dao = x）が書くフィールドは、コンストラクタ実引数の DaoA に絞らない" <<'EOF'
package pr;

import java.util.function.Consumer;

public class FfLambdaInit {
    private Dao dao;
    final Consumer<Dao> setter = x -> this.dao = x;
    FfLambdaInit(Dao d) { this.dao = d; }
    void go() { dao.find(); }
    public static void main(String[] args) {
        FfLambdaInit f = new FfLambdaInit(new DaoA());
        f.setter.accept(new DaoB());
        f.go();
    }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FIELD FfKeep FfKeep.go DaoB.find "対照: 入れ子のクラスと初期化ブロックが別のフィールドにだけ書くなら、dao はコンストラクタ実引数の DaoB に絞る" <<'EOF'
package pr;

public class FfKeep {
    private final Dao dao;
    private int count;
    { count = 1; }
    FfKeep(Dao d) { this.dao = d; }
    class Counter { void up() { count++; } }
    void go() { dao.find(); }
    public static void main(String[] args) {
        FfKeep f = new FfKeep(new DaoB());
        f.new Counter().up();
        f.go();
    }
}
EOF
expect_ absent FfKeep.go DaoA.find "同上（DaoA の行が無い）"

# super で修飾した書き込み（super.dao = …・Outer.super.dao = …・(super.dao) = …）も書き込み。入れ子の子クラスは外側の親クラスの
# private なフィールドに super.f で書ける。f491e2e は super.f を代入先として拾わず、初期化子の new DaoA() だけが入ると
# 判定して、this の読み取り（F:）も別のインスタンスの読み取り（O:）も DaoA に絞っていた（docs/value-safety-qa.md の Q26）
case_ listed FfSuper FfSuper.use DaoB.find "入れ子の子クラスのコンストラクタが super.dao = new DaoB() と書くなら、dao.find() を初期化子の DaoA に絞らない" <<'EOF'
package pr;

public class FfSuper {
    private Dao dao = new DaoA();
    void use() { dao.find(); }
    static class Sub extends FfSuper {
        Sub() { super.dao = new DaoB(); }
    }
    public static void main(String[] args) { new Sub().use(); }
}
EOF
expect_ listed FfSuper.use DaoA.find "同上（DaoA も残す）"

case_ listed FfSuperO FfSuperO.use DaoB.find "括弧で囲んだ (super.dao) = new DaoB() も書き込み。別のインスタンスの o.dao.find() も DaoA に絞らない" <<'EOF'
package pr;

public class FfSuperO {
    private Dao dao = new DaoA();
    void use(FfSuperO o) { o.dao.find(); }
    static class Sub extends FfSuperO {
        Sub() { (super.dao) = new DaoB(); }
    }
    public static void main(String[] args) { new FfSuperO().use(new Sub()); }
}
EOF

case_ listed FfOuterSuper FfOuterSuper.use DaoB.find "内部クラスからの Sub.super.dao = new DaoB() も書き込み。o.dao.find() を DaoA に絞らない" <<'EOF'
package pr;

public class FfOuterSuper {
    private Dao dao = new DaoA();
    void use(FfOuterSuper o) { o.dao.find(); }
    static class Sub extends FfOuterSuper {
        class In { void w() { Sub.super.dao = new DaoB(); } }
    }
    public static void main(String[] args) {
        Sub s = new Sub();
        s.new In().w();
        new FfOuterSuper().use(s);
    }
}
EOF

case_ listed FfCastThis FfCastThis.use DaoB.find "((FfCastThis) this).dao = new DaoB() も書き込み（別のインスタンスかもしれない書き込み）。DaoA に絞らない" <<'EOF'
package pr;

public class FfCastThis {
    private Dao dao = new DaoA();
    void use() { dao.find(); }
    static class Sub extends FfCastThis {
        Sub() { ((FfCastThis) this).dao = new DaoB(); }
    }
    public static void main(String[] args) { new Sub().use(); }
}
EOF

# ---------------------------------------------------------------------------
# コンストラクタ実引数は、今のオブジェクト（this）にしか当てない。別のインスタンスのフィールド（other.dao）や、
# 値を追えないレシーバ（拡張 for の変数・パターンの変数・配列の要素）への呼び出しに、今のオブジェクトの
# コンストラクタ実引数を当てると、別のオブジェクトの中身を取り違える（docs/value-safety-qa.md の Q19）
# ---------------------------------------------------------------------------
case_ listed OtherFld OtherFld.cmp DaoB.find "other.dao は other のフィールド。this のコンストラクタ実引数（DaoA）に絞らない" <<'EOF'
package pr;

public class OtherFld {
    private final Dao dao;
    OtherFld(Dao d) { this.dao = d; }
    void cmp(OtherFld other) { other.dao.find(); }
    public static void main(String[] args) { new OtherFld(new DaoA()).cmp(new OtherFld(new DaoB())); }
}
EOF

case_ reachable OtherInt OtherInt.check OtherInt.hit "other.mode は other のフィールド。this のコンストラクタ実引数（1）を当てて x == 2 を打ち切らない" <<'EOF'
package pr;

public class OtherInt {
    private final int mode;
    OtherInt(int m) { this.mode = m; }
    void cmp(OtherInt other) { check(other.mode); }
    void check(int x) { if (x == 2) { hit(); } }
    void hit() { System.out.println("h"); }
    public static void main(String[] args) { new OtherInt(1).cmp(new OtherInt(2)); }
}
EOF

case_ listed EachRecv EachRecv.go DaoB.find "拡張 for の変数 h への h.go() は this の呼び出しではない。今のオブジェクトのコンストラクタ実引数（DaoA）を当てない" <<'EOF'
package pr;

import java.util.List;

public class EachRecv {
    private final Dao dao;
    EachRecv(Dao d) { this.dao = d; }
    void go() { dao.find(); }
    void each(List<EachRecv> all) { for (EachRecv h : all) { h.go(); } }
    public static void main(String[] args) {
        new EachRecv(new DaoA()).each(List.of(new EachRecv(new DaoB())));
    }
}
EOF

case_ listed PatRecv PatRecv.go DaoB.find "パターンの変数 other への other.go() も this の呼び出しではない" <<'EOF'
package pr;

public class PatRecv {
    private final Dao dao;
    PatRecv(Dao d) { this.dao = d; }
    void go() { dao.find(); }
    void cmp(Object o) { if (o instanceof PatRecv other) { other.go(); } }
    public static void main(String[] args) { new PatRecv(new DaoA()).cmp(new PatRecv(new DaoB())); }
}
EOF

case_ listed ArrRecv ArrRecv.go DaoB.find "配列の要素への peers[0].go() も this の呼び出しではない" <<'EOF'
package pr;

public class ArrRecv {
    private final Dao dao;
    ArrRecv(Dao d) { this.dao = d; }
    void go() { dao.find(); }
    void first(ArrRecv[] peers) { peers[0].go(); }
    public static void main(String[] args) {
        new ArrRecv(new DaoA()).first(new ArrRecv[] { new ArrRecv(new DaoB()) });
    }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FIELD ThisDotInj ThisDotInj.use DaoB.find "対照: this.use() と this.d も今のオブジェクト。コンストラクタ実引数（DaoB）を引き継いで絞る" <<'EOF'
package pr;

public class ThisDotInj {
    private final Dao d;
    ThisDotInj(Dao d) { this.d = d; }
    public static void main(String[] args) { new ThisDotInj(new DaoB()).run(); }
    void run() { this.use(); }
    void use() { this.d.find(); }
}
EOF
expect_ absent ThisDotInj.use DaoA.find "同上（DaoA の行が無い）"

# 別のインスタンスのフィールドでも、どのインスタンスでも同じ値（初期化子の new・コンストラクタで入れるラムダ）なら絞る。
# コンストラクタ実引数から来た値だけは当てない（上の OtherFld・OtherInt）。01eb510 は別のインスタンスの読み取りを
# 値にしなかったので、どちらも CHA / UNEXPANDED:LAMBDA に戻っていた（docs/value-safety-qa.md の Q25）
case_ resolved:RESOLVED:DATAFLOW_FIELD OtherInit OtherInit.cmp DaoA.find "対照: o.dao の初期化子が new DaoA() だけなら、別のインスタンスでも DaoA に絞る" <<'EOF'
package pr;

public class OtherInit {
    private final Dao dao = new DaoA();
    void cmp(OtherInit o) { o.dao.find(); }
    public static void main(String[] args) { new OtherInit().cmp(new OtherInit()); }
}
EOF
expect_ absent OtherInit.cmp DaoB.find "同上（DaoB の行が無い）"

case_ resolved:RESOLVED:DATAFLOW_LAMBDA OtherLambda OtherLambda.main 'OtherLambda.lambda$new$0' "対照: どのコンストラクタでも同じラムダを入れる final なフィールドは、f.setter.accept でもそのラムダに繋ぐ" <<'EOF'
package pr;

import java.util.function.Consumer;

public class OtherLambda {
    private Dao dao;
    final Consumer<Dao> setter;
    OtherLambda(Dao d) { this.dao = d; this.setter = x -> this.dao = x; }
    void go() { dao.find(); }
    public static void main(String[] args) {
        OtherLambda f = new OtherLambda(new DaoA());
        f.setter.accept(new DaoB());
        f.go();
    }
}
EOF
expect_ listed OtherLambda.go DaoB.find "同上（ラムダが dao を書き換えるので、go の dao.find は DaoB も残す）"

case_ listed OtherNewArg OtherNewArg.cmp DaoB.find "コンストラクタ実引数を new の実引数に渡しても、new の型（Holder）が決まるだけ。o.h.dao は絞らない" <<'EOF'
package pr;

public class OtherNewArg {
    static class Holder {
        final Dao dao;
        Holder(Dao d) { this.dao = d; }
    }
    private final Holder h;
    OtherNewArg(Dao d) { this.h = new Holder(d); }
    void cmp(OtherNewArg o) { o.h.dao.find(); }
    public static void main(String[] args) { new OtherNewArg(new DaoA()).cmp(new OtherNewArg(new DaoB())); }
}
EOF
expect_ listed OtherNewArg.cmp DaoA.find "同上（DaoA も残す）"

case_ resolved:UNEXPANDED:LAMBDA OtherCap 'OtherCap.<init>' Runnable.run "別のインスタンスのラムダが捕捉した引数（d）を使うなら、そのラムダに繋がない（コンストラクタの中で呼ぶと、今のオブジェクトの d を当てて DaoB に絞ってしまう）" <<'EOF'
package pr;

public class OtherCap {
    final Runnable r;
    OtherCap(Dao d, OtherCap prev) {
        this.r = () -> d.find();
        if (prev != null) { prev.r.run(); }
    }
    static OtherCap make(Dao d) { return new OtherCap(d, null); }
    public static void main(String[] args) { new OtherCap(new DaoB(), make(new DaoA())); }
}
EOF

# ---------------------------------------------------------------------------
# 拡張 for の要素の出所は、要素を詰めた値だけと言い切れるコレクション（この本体で引数なしの new をした
# java.util のローカル変数で、再代入せず、要素を足すメソッドのレシーバと拡張 for にしか使わない）に限る。
# コンストラクタの実引数・addAll・他のメソッドへ渡す・別名・再代入・引数のコレクション・listIterator().add・
# list::add で入った要素を見落とすと、見えた add の値だけに絞る（docs/value-safety-qa.md の Q20）
# ---------------------------------------------------------------------------
case_ listed ElemCtor ElemCtor.run DaoB.find "new ArrayList<>(List.of(new DaoB())) の要素は add した DaoA だけではない" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemCtor {
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>(List.of(new DaoB()));
        list.add(new DaoA());
        for (Dao d : list) { d.find(); }
    }
}
EOF
expect_ listed ElemCtor.run DaoA.find "同上（DaoA も残す）"

case_ listed ElemPass ElemPass.run DaoB.find "別のメソッドへ渡して詰めてもらった要素（fill(list)）も残す" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemPass {
    static void fill(List<Dao> l) { l.add(new DaoB()); }
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        fill(list);
        for (Dao d : list) { d.find(); }
    }
}
EOF

case_ listed ElemAlias ElemAlias.run DaoB.find "別名（alias = list）から add した要素も残す" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemAlias {
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        List<Dao> alias = list;
        alias.add(new DaoB());
        for (Dao d : list) { d.find(); }
    }
}
EOF

case_ listed ElemAddAll ElemAddAll.run DaoB.find "addAll で入った要素も残す" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemAddAll {
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        list.addAll(List.of(new DaoB()));
        for (Dao d : list) { d.find(); }
    }
}
EOF

case_ listed ElemReset ElemReset.run DaoB.find "再代入（list = make();）したコレクションの要素も残す" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemReset {
    static List<Dao> make() { return new ArrayList<>(List.of(new DaoB())); }
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        list = make();
        for (Dao d : list) { d.find(); }
    }
}
EOF

case_ listed ElemParam ElemParam.run DaoB.find "引数のコレクションは呼び出し元が詰めた要素（DaoB）も持つ" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemParam {
    static void run(List<Dao> tasks) {
        tasks.add(new DaoA());
        for (Dao d : tasks) { d.find(); }
    }
    public static void main(String[] args) {
        List<Dao> l = new ArrayList<>();
        l.add(new DaoB());
        run(l);
    }
}
EOF

case_ listed ElemIter ElemIter.run DaoB.find "listIterator().add で入った要素も残す" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemIter {
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        list.listIterator().add(new DaoB());
        for (Dao d : list) { d.find(); }
    }
}
EOF

case_ listed ElemRef ElemRef.run DaoB.find "メソッド参照 list::add で入った要素も残す" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ElemRef {
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        Stream.of(new DaoB()).forEach(list::add);
        for (Dao d : list) { d.find(); }
    }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_NEW ElemOnly ElemOnly.run DaoA.find "対照: new した空のリストに add した値だけなら、要素（DaoA）に絞る（size() の問い合わせは要素を変えない）" <<'EOF'
package pr;

import java.util.ArrayList;
import java.util.List;

public class ElemOnly {
    public static void main(String[] args) { run(); }
    static void run() {
        List<Dao> list = new ArrayList<>();
        list.add(new DaoA());
        if (list.size() > 0) {
            for (Dao d : list) { d.find(); }
        }
    }
}
EOF
expect_ absent ElemOnly.run DaoB.find "同上（DaoB の行が無い）"

# ---------------------------------------------------------------------------
# 型名で書いたメソッド参照（Type::instMethod）は、関数型インターフェースのメソッドの第 1 実引数がレシーバで、
# 2 番目からが参照先の引数（JLS 15.13.3）。位置をずらさずに当てると、別の実引数で条件を判定して打ち切ったり、
# 引数の具象型を取り違えたりする（docs/value-safety-qa.md の Q21）
# ---------------------------------------------------------------------------
case_ reachable MrefUn MrefMode.chk MrefUn.hit "c.accept(B, A) を Mode::chk へ繋ぐとき、other は 2 番目の実引数（A）。1 番目（B）を当てて other == A を打ち切らない" <<'EOF'
package pr;

import java.util.function.BiConsumer;

enum MrefMode {
    A, B;
    void chk(MrefMode other) {
        if (other == MrefMode.A) { MrefUn.hit(); }
        if (other == MrefMode.B) { MrefUn.miss(); }
    }
}

public class MrefUn {
    public static void main(String[] args) { run(MrefMode::chk); }
    static void run(BiConsumer<MrefMode, MrefMode> c) { c.accept(MrefMode.B, MrefMode.A); }
    static void hit() { System.out.println("h"); }
    static void miss() { System.out.println("m"); }
}
EOF
expect_ via:MrefUn.run:pruned MrefMode.chk MrefUn.miss "対照: 位置をずらして当てるので、other == B（実際は A）は打ち切る"

case_ via:MrefMerge.run:resolved:RESOLVED:DATAFLOW_PARAM MrefMerge MrefMerger.merge DaoB.find "c.accept(new MrefMerger(), new DaoB()) の Merger::merge の other は DaoB（レシーバの MrefMerger ではない）" <<'EOF'
package pr;

import java.util.function.BiConsumer;

class MrefMerger implements Dao {
    public void find() { System.out.println("m"); }
    void merge(Dao other) { other.find(); }
}

public class MrefMerge {
    public static void main(String[] args) { run(MrefMerger::merge); }
    static void run(BiConsumer<MrefMerger, Dao> c) { c.accept(new MrefMerger(), new DaoB()); }
}
EOF
expect_ via:MrefMerge.run:absent MrefMerger.merge MrefMerger.find "同上（レシーバの MrefMerger.find に絞った行が無い）"

case_ via:MrefStatic.run:pruned MrefStatic MrefStatic.chk MrefStatic.hit "対照: static メソッドの参照（MrefStatic::chk）は実引数の位置のまま当てる。a は B なので a == A は打ち切る" <<'EOF'
package pr;

import java.util.function.BiConsumer;

public class MrefStatic {
    public static void main(String[] args) { run(MrefStatic::chk); }
    static void run(BiConsumer<MrefMode, MrefMode> c) { c.accept(MrefMode.B, MrefMode.A); }
    static void chk(MrefMode a, MrefMode b) { if (a == MrefMode.A) { hit(); } }
    static void hit() { System.out.println("h"); }
}
EOF

# ---------------------------------------------------------------------------
# リフレクションの invoke は、private と static のメソッドを受け手の実行時のクラスで選び直さない（上書きされない・
# 受け手を使わない）。引数型が分からない getMethod は、親から継承した多重定義も候補にする（docs/value-safety-qa.md の Q22）
# ---------------------------------------------------------------------------
case_ listed ReflOverload ReflOverload.run ReflOvP.m "C.class.getMethod(\"m\", types) で引数型が分からなければ、親から継承した ReflOvP.m(int) も候補に残す" <<'EOF'
package pr;

import java.lang.reflect.Method;

class ReflOvP { public void m(int x) { System.out.println("p"); } }
class ReflOvC extends ReflOvP { public void m(String s) { System.out.println("c"); } }

public class ReflOverload {
    static void run(Class<?>[] types, Object arg) throws Exception {
        Method mm = ReflOvC.class.getMethod("m", types);
        mm.invoke(new ReflOvC(), arg);
    }
    public static void main(String[] args) throws Exception { run(new Class<?>[] { int.class }, 5); }
}
EOF
expect_ listed ReflOverload.run ReflOvC.m "同上（ReflOvC.m(String) も残す）"

case_ resolved:RESOLVED:REFLECTION ReflPriv ReflPriv.main ReflPrivP.m "getDeclaredMethod で引いた private の ReflPrivP.m は、invoke(new ReflPrivC()) でも上書きされない（ReflPrivC.m に繋がない）" <<'EOF'
package pr;

import java.lang.reflect.Method;

class ReflPrivP { private void m() { System.out.println("p"); } }
class ReflPrivC extends ReflPrivP { public void m() { System.out.println("c"); } }

public class ReflPriv {
    public static void main(String[] args) throws Exception {
        Method mm = ReflPrivP.class.getDeclaredMethod("m");
        mm.setAccessible(true);
        mm.invoke(new ReflPrivC());
    }
}
EOF
expect_ absent ReflPriv.main ReflPrivC.m "同上（ReflPrivC.m の行が無い）"

case_ resolved:RESOLVED:REFLECTION ReflStatic ReflStatic.main ReflStP.s "static の ReflStP.s は受け手（new ReflStC()）を使わない。部分型の ReflStC.s に繋がない" <<'EOF'
package pr;

import java.lang.reflect.Method;

class ReflStP { public static void s() { System.out.println("p"); } }
class ReflStC extends ReflStP { public static void s() { System.out.println("c"); } }

public class ReflStatic {
    public static void main(String[] args) throws Exception {
        Method mm = ReflStP.class.getMethod("s");
        mm.invoke(new ReflStC());
    }
}
EOF
expect_ absent ReflStatic.main ReflStC.s "同上（ReflStC.s の行が無い）"

# ---------------------------------------------------------------------------
# DI（段 5）で唯一の Bean に絞るのは、レシーバがコンテナの値を入れうる注入点のときだけ。Bean でないクラスの
# 引数やフィールドには利用者のコードが何を入れてもよい。Bean のコンストラクタでも、経路で実際に渡した値の
# 具象型が分かればそちらを採る（docs/spring-di-qa.md の Q5・Q6）
# ---------------------------------------------------------------------------
case_ listed SbPlainParam SbPlainParam.process SbPpB.find "Bean でないクラスの static メソッドの引数（process(new SbPpB())）は、唯一の Bean の SbPpA に絞らない" <<'EOF'
package pr;

interface SbPpI { void find(); }
@Repository class SbPpA implements SbPpI { public void find() { System.out.println("a"); } }
class SbPpB implements SbPpI { public void find() { System.out.println("b"); } }

public class SbPlainParam {
    static void process(SbPpI d) { d.find(); }
    public static void main(String[] args) { process(new SbPpB()); }
}
EOF
expect_ absent SbPlainParam.process SbPpA.find "同上（SbPpA だけに絞った行が無い）"

case_ listed SbPlainField SbPlainField.go SbPfB.find "Bean でないクラスのフィールド（注釈なし）は、唯一の Bean の SbPfA に絞らない（渡した値は SbPfA か SbPfB）" <<'EOF'
package pr;

interface SbPfI { void find(); }
@Repository class SbPfA implements SbPfI { public void find() { System.out.println("a"); } }
class SbPfB implements SbPfI { public void find() { System.out.println("b"); } }

public class SbPlainField {
    private final SbPfI dao;
    SbPlainField(SbPfI d) { this.dao = d; }
    void go() { dao.find(); }
    public static void main(String[] args) {
        SbPfI d = args.length > 0 ? new SbPfA() : new SbPfB();
        new SbPlainField(d).go();
    }
}
EOF
expect_ listed SbPlainField.go SbPfA.find "同上（SbPfA も残す）"

case_ resolved:RESOLVED:DATAFLOW_FIELD SbManual SbManual.go SbMnB.find "Bean のコンストラクタでも、利用者が new SbManual(new SbMnB()) と渡した経路では、渡した SbMnB を採る（段 5 の SbMnA に決めない）" <<'EOF'
package pr;

interface SbMnI { void find(); }
@Repository class SbMnA implements SbMnI { public void find() { System.out.println("a"); } }
class SbMnB implements SbMnI { public void find() { System.out.println("b"); } }

@Repository
public class SbManual {
    private final SbMnI dao;
    SbManual(SbMnI d) { this.dao = d; }
    void go() { dao.find(); }
    public static void main(String[] args) { new SbManual(new SbMnB()).go(); }
}
EOF
expect_ absent SbManual.go SbMnA.find "同上（SbMnA の行が無い）"

case_ resolved:RESOLVED:SPRING_DI SbCtorKeep SbCtorKeep.run SbCkA.find "対照: ステレオタイプの Bean のコンストラクタで受け取るフィールド（注釈なし）は注入点。段 5 で SbCkA に絞る" <<'EOF'
package pr;

interface SbCkI { void find(); }
@Repository class SbCkA implements SbCkI { public void find() { System.out.println("a"); } }
class SbCkB implements SbCkI { public void find() { System.out.println("b"); } }

@Repository
public class SbCtorKeep {
    private final SbCkI r;
    SbCtorKeep(SbCkI r) { this.r = r; }
    void run() { r.find(); }
}
EOF
expect_ absent SbCtorKeep.run SbCkB.find "同上（SbCkB の行が無い）"

case_ resolved:RESOLVED:SPRING_DI SbSetterKeep SbSetterKeep.setRepo SbSkA.find "対照: @Autowired を付けたメソッドの引数は注入点。段 5 で SbSkA に絞る" <<'EOF'
package pr;

interface SbSkI { void find(); }
@Repository class SbSkA implements SbSkI { public void find() { System.out.println("a"); } }
class SbSkB implements SbSkI { public void find() { System.out.println("b"); } }

public class SbSetterKeep {
    @Autowired void setRepo(SbSkI r) { r.find(); }
}
EOF
expect_ absent SbSetterKeep.setRepo SbSkB.find "同上（SbSkB の行が無い）"

# ---------------------------------------------------------------------------
# フレームワークが生成の後に書くフィールド。@Autowired・@Inject・@Resource・@Value のようにフィールドに注釈が付いている
# か、宣言した型にステレオタイプ以外の注釈（@ConfigurationProperties・@Entity など）が付いていれば、リフレクションや
# 生成されたコードがソースに見えない書き込みをする。f491e2e は注釈を見ずに、初期化子の値（@Autowired(required = false) の
# 既定）だけが入ると判定していた。this の読み取り（F:）も別のインスタンスの読み取り（O:）も同じ（docs/value-safety-qa.md の Q27）。
# DI（段 5）も、ソースが引数でない値を入れるフィールド（初期化子・new の代入）を注入点にしない。コンテナが入れた値だけが
# 来るとは言えない（docs/spring-di-qa.md の Q15）
# ---------------------------------------------------------------------------
case_ listed FwAuto FwAuto.use DaoB.find "@Autowired(required = false) の初期化子（new DaoA()）は既定にすぎない。注入されれば別の値になるので絞らない" <<'EOF'
package pr;

public class FwAuto {
    @Autowired(required = false)
    private Dao dao = new DaoA();
    void use() { dao.find(); }
    public static void main(String[] args) { new FwAuto().use(); }
}
EOF
expect_ listed FwAuto.use DaoA.find "同上（DaoA も残す）"

case_ listed FwAutoO FwAutoO.cmp DaoB.find "同上を別のインスタンス（o.dao）から読んでも絞らない" <<'EOF'
package pr;

public class FwAutoO {
    @Autowired(required = false)
    private Dao dao = new DaoA();
    void cmp(FwAutoO o) { o.dao.find(); }
    public static void main(String[] args) { new FwAutoO().cmp(new FwAutoO()); }
}
EOF

case_ listed FwInject FwInject.use DaoB.find "@Inject の初期化子も既定にすぎない" <<'EOF'
package pr;

import pr.di.Inject;

public class FwInject {
    @Inject
    private Dao dao = new DaoA();
    void use() { dao.find(); }
    public static void main(String[] args) { new FwInject().use(); }
}
EOF

case_ listed FwResource FwResource.use DaoB.find "@Resource(name = \"daoB\") の初期化子も既定にすぎない" <<'EOF'
package pr;

public class FwResource {
    @Resource(name = "daoB")
    private Dao dao = new DaoA();
    void use() { dao.find(); }
    public static void main(String[] args) { new FwResource().use(); }
}
EOF

case_ listed FwValue FwValue.cmp DaoB.find "@Value(\"#{daoB}\") の初期化子も既定にすぎない（別のインスタンスから読む形）" <<'EOF'
package pr;

public class FwValue {
    @Value("#{daoB}")
    private Dao dao = new DaoA();
    void cmp(FwValue o) { o.dao.find(); }
    public static void main(String[] args) { new FwValue().cmp(new FwValue()); }
}
EOF

case_ listed FwProps FwProps.use DaoB.find "@ConfigurationProperties の型のフィールドはフレームワークが結び付ける。注釈の無いフィールドでも初期化子に絞らない" <<'EOF'
package pr;

@ConfigurationProperties("app")
public class FwProps {
    private Dao dao = new DaoA();
    void use() { dao.find(); }
    public static void main(String[] args) { new FwProps().use(); }
}
EOF

case_ resolved:RESOLVED:DATAFLOW_FIELD FwDeprecated FwDeprecated.use DaoA.find "対照: java.lang の注釈（@Deprecated）は記録しないので、フィールドは初期化子の DaoA に絞る" <<'EOF'
package pr;

public class FwDeprecated {
    @Deprecated
    private final Dao dao = new DaoA();
    void use() { dao.find(); }
    public static void main(String[] args) { new FwDeprecated().use(); }
}
EOF
expect_ absent FwDeprecated.use DaoB.find "同上（DaoB の行が無い）"

case_ resolved:RESOLVED:DATAFLOW_FIELD SnStereo SnStereo.use DaoA.find "対照: ステレオタイプの Bean（@Component）の注釈の無いフィールドにコンテナは書かない。初期化子の DaoA に絞る" <<'EOF'
package pr;

@Component
public class SnStereo {
    private final Dao dao = new DaoA();
    void use() { dao.find(); }
}
EOF
expect_ absent SnStereo.use DaoB.find "同上（DaoB の行が無い）"

case_ listed SnReq SnReq.use SnRqA.find "@Autowired(required = false) の初期化子（new SnRqA()）があるフィールドは、段 5 で唯一の Bean（SnRqB）に絞らない（Bean が無ければ既定が残る・コンテナの外で new すれば既定のまま）" <<'EOF'
package pr;

interface SnRqI { void find(); }
class SnRqA implements SnRqI { public void find() { System.out.println("a"); } }
@Repository class SnRqB implements SnRqI { public void find() { System.out.println("b"); } }

@Component
public class SnReq {
    @Autowired(required = false)
    private SnRqI dao = new SnRqA();
    void use() { dao.find(); }
}
EOF
expect_ listed SnReq.use SnRqB.find "同上（注入される SnRqB も残す）"

case_ listed SnReset SnReset.use SnRsA.find "ステレオタイプの Bean のフィールドでも、ソースが引数でない値（new SnRsA()）を入れるなら、段 5 で唯一の Bean（SnRsB）に絞らない" <<'EOF'
package pr;

interface SnRsI { void find(); }
class SnRsA implements SnRsI { public void find() { System.out.println("a"); } }
@Repository class SnRsB implements SnRsI { public void find() { System.out.println("b"); } }

@Component
public class SnReset {
    private SnRsI dao;
    SnReset(SnRsI d) { this.dao = d; }
    void reset() { this.dao = new SnRsA(); }
    void use() { dao.find(); }
}
EOF
expect_ listed SnReset.use SnRsB.find "同上（注入される SnRsB も残す）"

# ---------------------------------------------------------------------------
# 別の型（子クラス・内部クラス・ほかのパッケージの型）から、private でないフィールドへの書き込み。
# 39 版の書き手は private なフィールドへの書き込みしか拾わず、ソースが引数でない値を入れるフィールドなのに段 5 が
# 唯一の Bean に絞っていた。J 行は書いた側のファイルのブロックに載り、読み手はブロックをまたいで集める
# （docs/spring-di-qa.md の Q15）
# ---------------------------------------------------------------------------
cat > "$SRC/SnSubBase.java" <<'EOF'
package pr;

interface SnSbI { void find(); }
class SnSbA implements SnSbI { public void find() { System.out.println("a"); } }
@Repository class SnSbB implements SnSbI { public void find() { System.out.println("b"); } }

public abstract class SnSubBase {
    @Autowired
    protected SnSbI dao;
    void go() { dao.find(); }
}
EOF
case_ listed SnSub SnSubBase.go SnSbA.find "別のファイルの子クラスのコンストラクタが this.dao = new SnSbA() と書く protected なフィールドは、段 5 で唯一の Bean（SnSbB）に絞らない" <<'EOF'
package pr;

@Component
public class SnSub extends SnSubBase {
    SnSub() { this.dao = new SnSbA(); }
}
EOF
expect_ listed SnSubBase.go SnSbB.find "同上（注入される SnSbB も残す）"

case_ listed SnInner SnInner.go SnInA.find "内部クラスが SnInner.this.dao = new SnInA() と書くパッケージ private なフィールドは、段 5 で唯一の Bean（SnInB）に絞らない" <<'EOF'
package pr;

interface SnInI { void find(); }
class SnInA implements SnInI { public void find() { System.out.println("a"); } }
@Repository class SnInB implements SnInI { public void find() { System.out.println("b"); } }

@Component
public class SnInner {
    SnInI dao;
    SnInner(SnInI d) { this.dao = d; }
    void go() { dao.find(); }
    class Resetter { void reset() { SnInner.this.dao = new SnInA(); } }
}
EOF
expect_ listed SnInner.go SnInB.find "同上（注入される SnInB も残す）"

cat > "$SRC/SnOtI.java" <<'EOF'
package pr;

public interface SnOtI { void find(); }
EOF
cat > "$SRC/SnOtA.java" <<'EOF'
package pr;

public class SnOtA implements SnOtI { public void find() { System.out.println("a"); } }
EOF
mkdir -p "$SRC/wire"
cat > "$SRC/wire/SnOtWire.java" <<'EOF'
package pr.wire;

public class SnOtWire {
    public static void wire(pr.SnOther s) { s.dao = new pr.SnOtA(); }
}
EOF
case_ listed SnOther SnOther.go SnOtA.find "ほかのパッケージのクラスが s.dao = new SnOtA() と書く public なフィールドは、段 5 で唯一の Bean（SnOtB）に絞らない" <<'EOF'
package pr;

@Repository class SnOtB implements SnOtI { public void find() { System.out.println("b"); } }

@Component
public class SnOther {
    public SnOtI dao;
    SnOther(SnOtI d) { this.dao = d; }
    void go() { dao.find(); }
}
EOF
expect_ listed SnOther.go SnOtB.find "同上（注入される SnOtB も残す）"

cat > "$SRC/SnSuperSub.java" <<'EOF'
package pr;

public class SnSuperSub extends SnSuper {
    SnSuperSub() {
        super(null);
        super.dao = new SnSuA();
    }
}
EOF
case_ listed SnSuper SnSuper.go SnSuA.find "別のファイルの子クラスが super.dao = new SnSuA() と書く protected なフィールドは、段 5 で唯一の Bean（SnSuB）に絞らない" <<'EOF'
package pr;

interface SnSuI { void find(); }
class SnSuA implements SnSuI { public void find() { System.out.println("a"); } }
@Repository class SnSuB implements SnSuI { public void find() { System.out.println("b"); } }

@Component
public class SnSuper {
    protected SnSuI dao;
    SnSuper(SnSuI d) { this.dao = d; }
    void go() { dao.find(); }
}
EOF
expect_ listed SnSuper.go SnSuB.find "同上（注入される SnSuB も残す）"

cat > "$SRC/SnKeepWriter.java" <<'EOF'
package pr;

public class SnKeepWriter {
    static void touch(SnKeepPub k) { k.note = new StringBuilder(); }
}
EOF
case_ resolved:RESOLVED:SPRING_DI SnKeepPub SnKeepPub.go SnKpB.find "対照: よその型の書き込みはフィールドごと。別のフィールド（note）に new を書かれても、コンストラクタで受け取るだけの dao は段 5 で SnKpB に絞る" <<'EOF'
package pr;

interface SnKpI { void find(); }
class SnKpA implements SnKpI { public void find() { System.out.println("a"); } }
@Repository class SnKpB implements SnKpI { public void find() { System.out.println("b"); } }

@Component
public class SnKeepPub {
    SnKpI dao;
    StringBuilder note;
    SnKeepPub(SnKpI d) { this.dao = d; }
    void go() { dao.find(); }
}
EOF
expect_ absent SnKeepPub.go SnKpA.find "同上（SnKpA の行が無い）"

# ---------------------------------------------------------------------------
# Objects.requireNonNull(d) は d をそのまま返す。書き込みの値を引数として読む（docs/value-safety-qa.md の Q28）。
# 39 版は値をメソッドの戻り値として読み、コンストラクタ注入を注入点から外して CHA に戻していた（精度の対照）
# ---------------------------------------------------------------------------
case_ resolved:RESOLVED:SPRING_DI SnRnn SnRnn.go SnRnB.find "対照: コンストラクタ注入を Objects.requireNonNull(d, \"dao\") で包んでも引数を入れる書き込み。段 5 で SnRnB に絞る" <<'EOF'
package pr;

import java.util.Objects;

interface SnRnI { void find(); }
class SnRnA implements SnRnI { public void find() { System.out.println("a"); } }
@Repository class SnRnB implements SnRnI { public void find() { System.out.println("b"); } }

@Component
public class SnRnn {
    private final SnRnI dao;
    SnRnn(SnRnI d) { this.dao = Objects.requireNonNull(d, "dao"); }
    void go() { dao.find(); }
}
EOF
expect_ absent SnRnn.go SnRnA.find "同上（SnRnA の行が無い）"

case_ resolved:RESOLVED:DATAFLOW_FIELD RnnPath RnnPath.use DaoB.find "対照: this.dao = requireNonNull(d)（static import）のフィールドにも、経路のコンストラクタ実引数（DaoB）を当てて絞る" <<'EOF'
package pr;

import static java.util.Objects.requireNonNull;

public class RnnPath {
    private final Dao dao;
    RnnPath(Dao d) { this.dao = requireNonNull(d); }
    void use() { dao.find(); }
    public static void main(String[] args) { new RnnPath(new DaoB()).use(); }
}
EOF
expect_ absent RnnPath.use DaoA.find "同上（DaoA の行が無い）"

case_ listed RnnElse RnnElse.use DaoA.find "Objects.requireNonNullElse(d, new DaoA()) は d を返すとは限らない（null なら既定の DaoA）。引数として読まず、DaoB に絞らない" <<'EOF'
package pr;

import java.util.Objects;

public class RnnElse {
    private final Dao dao;
    RnnElse(Dao d) { this.dao = Objects.requireNonNullElse(d, new DaoA()); }
    void use() { dao.find(); }
    public static void main(String[] args) { new RnnElse(new DaoB()).use(); }
}
EOF
expect_ listed RnnElse.use DaoB.find "同上（渡した DaoB も残す）"

# ---------------------------------------------------------------------------
# 実際に動く実装の探し方（JLS 8.4.8・9.4.1、JVMS 5.4.6）。親クラスの連鎖を根まで見てから親インターフェースを見る。
# 42 版までの読み手は親型を名前順の幅優先で混ぜて辿り、1 段上のインターフェース（JDK の AutoCloseable・Iterable・
# Runnable や、名前が先に並ぶ default）の宣言を 2 段上の親クラスの実装より先に採って、動く実装を落としていた。
# 親クラスの private メソッドは継承されないので、実装にも、try-with-resources・拡張 for の呼び出し先にもしない
# ---------------------------------------------------------------------------
case_ listed Dtwr Dtwr.run DtwrBase.close "try-with-resources の close() は親クラスの連鎖から継承した実装（AutoCloseable.close ではない）" <<'EOF'
package pr;

class DtwrBase { public void close() { System.out.println("c"); } }
class DtwrMid extends DtwrBase { }
class DtwrRes extends DtwrMid implements AutoCloseable { }

public class Dtwr {
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception { try (DtwrRes r = new DtwrRes()) { System.out.println("t"); } }
}
EOF

case_ listed Dfe Dfe.run DfeBase.iterator "拡張 for の iterator() は親クラスの連鎖から継承した実装（Iterable.iterator ではない）" <<'EOF'
package pr;

class DfeBase {
    public java.util.Iterator<String> iterator() { return java.util.List.of("x").iterator(); }
}
class DfeMid extends DfeBase { }
class DfeColl extends DfeMid implements Iterable<String> { }

public class Dfe {
    public static void main(String[] args) { run(); }
    static void run() { for (String s : new DfeColl()) { System.out.println(s); } }
}
EOF

case_ listed DRun DRun.run DRunBase.flush "JDK のインターフェース（Flushable）の型で呼んでも、実装は親クラスの DRunBase.flush（ソースの無い Flushable.flush ではない。Runnable と同じ形で、Runnable にすると他のケースのラムダの候補が変わる）" <<'EOF'
package pr;

class DRunBase { public void flush() { System.out.println("f"); } }
class DRunTask extends DRunBase implements java.io.Flushable { }

public class DRun {
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception { java.io.Flushable f = new DRunTask(); f.flush(); }
}
EOF

case_ listed DtwrP DtwrP.run DtwrPApi.close "親クラスの private の close() は継承されないので、資源の close() は親インターフェースの default" <<'EOF'
package pr;

class DtwrPBase {
    private void close() { System.out.println("p"); }
    void use() { close(); }
}
interface DtwrPApi extends AutoCloseable { default void close() { System.out.println("d"); } }
class DtwrPRes extends DtwrPBase implements DtwrPApi { }

public class DtwrP {
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception { try (DtwrPRes r = new DtwrPRes()) { System.out.println("t"); } }
}
EOF
expect_ absent DtwrP.run DtwrPBase.close "対照: 親クラスの private の close() は try-with-resources から呼ばれない"

case_ listed DrRet DrRet.use DaoB.find "戻り値: 親クラスの実装（DaoB を返す）が動くので、default の戻り値（DaoA）に絞らない" <<'EOF'
package pr;

interface DrFac { default Dao create() { return new DaoA(); } }
class DrBase { public Dao create() { return new DaoB(); } }
class DrMid extends DrBase { }
class DrImpl extends DrMid implements DrFac { }

public class DrRet {
    public static void main(String[] args) { use(new DrImpl()); }
    static void use(DrFac f) { f.create().find(); }
}
EOF
expect_ listed DrRet.use DrBase.create "戻り値: 動く実装は親クラスの DrBase.create（名前が先に並ぶ DrFac の default ではない）"

# 親クラスが jar のクラスなら、その（表に無い）create() が default より勝ちうる。default の戻り値（DaoA）に絞らない。
# jar（work/lib/prlib.jar。lib の *.jar は依存 jar として読まれる）は解析の前に作る
mkdir -p work/libsrc/prlib work/libcls work/lib
cat > work/libsrc/prlib/Holder.java <<'EOF'
package prlib;

public class Holder<T> {
    private final T v;
    public Holder(T v) { this.v = v; }
    public T create() { return v; }
}
EOF
"$JAVAC_BIN" -nowarn -encoding UTF-8 -d work/libcls work/libsrc/prlib/Holder.java \
    && "$(dirname "$JAVAC_BIN")/jar" --create --file work/lib/prlib.jar -C work/libcls . \
    || ng "jar（work/lib/prlib.jar）を作れませんでした"
case_ listed JarHold JarHold.use DaoB.find "戻り値: 親クラスが jar のクラス（prlib.Holder）なら、その create() が default より勝ちうる。default の戻り値（DaoA）に絞らない" <<'EOF'
package pr;

interface JhFac { default Dao create() { return new DaoA(); } }
class JhImpl extends prlib.Holder<Dao> implements JhFac { JhImpl() { super(new DaoB()); } }

public class JarHold {
    public static void main(String[] args) { use(new JhImpl()); ref(); }
    static void use(JhFac f) { f.create().find(); }
    static void ref() {
        JhFac f = new JhImpl();
        java.util.function.Supplier<Dao> s = f::create;
        s.get().find();
    }
}
EOF
expect_ listed JarHold.ref DaoB.find "戻り値: メソッド参照の束縛したレシーバ（JhImpl）から引いた default も、jar のクラスが挟まるので本体の戻り値に使わない"

# ---------------------------------------------------------------------------
# 解析して確かめる
# ---------------------------------------------------------------------------
( cd work && "$JAVA_BIN" -cp "$CLASSES:$CP" jche.CallHierarchyExporter config.properties ) > work/run.log 2>&1
CSV=$(ls -d work/out/*/ 2>/dev/null | sort | tail -1)call-hierarchy.csv
if [ ! -f "$CSV" ]; then
    ng "解析できませんでした（test/pruning/work/run.log）"
    grep -v JAVA_TOOL_OPTIONS work/run.log | tail -5
    echo "FAIL"; exit 1
fi
grep -q "compile errors\|syntax errors" work/run.log \
    && ng "題材にコンパイルエラーがあります（test/pruning/work/run.log）" \
    || ok "題材をエラーなしで解析できた"

# 呼び出し元（Class.method）から呼び出し先（Class.method）への行。列は caller,callee,resolved-by,…
# 呼び出し元・呼び出し先が / で始まれば無名パッケージのクラス
rows_of() {
    local c="at pr.$1(" d=$2
    [ "${1#/}" != "$1" ] && c="at ${1#/}("
    d=${d#/}
    awk -F, -v c="$c" -v d="$d" 'index($1, c) == 1 && $2 == d' "$CSV"
}

for c in "${CASES[@]}"; do
    IFS=$'\t' read -r expect caller callee desc <<< "$c"
    rows=$(rows_of "$caller" "$callee")
    label="$caller -> $callee: $desc"
    if [ "${expect#via:}" != "$expect" ]; then
        # via:<Class.method>:<期待> … 経路（root 列と call-hierarchy 列）に <Class.method> を通る行だけで <期待> を見る
        rest=${expect#via:}
        through=${rest%%:*}
        expect=${rest#*:}
        rows=$(awk -F, -v m="$through" '{ for (i = 5; i <= NF; i++) if ($i == m) { print; next } }' <<< "$rows")
        label="$label（$through を通る経路）"
    fi
    if [ "$expect" = absent ]; then
        if [ -z "$rows" ]; then
            ok "$label"
        else
            ng "$label（行がある）"; echo "       $(head -1 <<< "$rows")"
        fi
        continue
    fi
    if [ -z "$rows" ]; then
        ng "$label（行がありません）"
        continue
    fi
    case $expect in
        reachable|listed)
            if grep -q '\[UNREACHABLE\]' <<< "$rows"; then
                ng "$label（[UNREACHABLE] が付いている）"
                echo "       $(head -1 <<< "$rows")"
            else
                ok "$label"
            fi ;;
        pruned)
            if grep -qv '\[UNREACHABLE\]' <<< "$rows"; then
                ng "$label（打ち切られていない）"
                echo "       $(head -1 <<< "$rows")"
            else
                ok "$label"
            fi ;;
        pruned=*)
            want=${expect#pruned=}
            if grep -qv '\[UNREACHABLE\]' <<< "$rows"; then
                ng "$label（打ち切られていない）"
                echo "       $(head -1 <<< "$rows")"
            elif ! grep -qF "= $want)" <<< "$rows"; then
                ng "$label（注記の値が $want ではない）"
                echo "       $(head -1 <<< "$rows")"
            else
                ok "$label"
            fi ;;
        from:*)
            want=${expect#from:}
            if awk -F, -v r="$want" '$5 == r { f = 1 } END { exit !f }' <<< "$rows"; then
                ok "$label"
            else
                ng "$label（起点が $want の行がありません）"
                echo "       $(head -1 <<< "$rows")"
            fi ;;
        resolved:*)
            want=${expect#resolved:}
            got=$(head -1 <<< "$rows" | cut -d, -f3)
            [ "$got" = "$want" ] && ok "$label" \
                || { ng "$label（resolved-by が $got。期待は $want）"; echo "       $(head -1 <<< "$rows")"; } ;;
        *)
            ng "未知の期待 $expect（$label）" ;;
    esac
done

# 絵文字で切れる条件式の注記。サロゲートペアの片方だけが残ると、CSV では置換文字（?）になる
emoji=$(rows_of EmojiCut.run EmojiCut.target)
if grep -q -F 'A…' <<< "$emoji" && ! grep -q -F 'A?…' <<< "$emoji"; then
    ok "EmojiCut.run -> EmojiCut.target: 注記の条件式は絵文字の手前で切れている（サロゲートの片方が残らない）"
else
    ng "EmojiCut.run -> EmojiCut.target: 注記の条件式の切れ目が期待と違います"
    echo "       $(head -1 <<< "$emoji")"
fi

# ---------------------------------------------------------------------------
# 値を読まない指定（dataflow.enabled=false）の @Bean。R 行を読まないので、@Bean メソッドが返す具象型は
# return new RepoB(); でも決まらない。その Bean を数えないと、段 5 が @Repository の RepoA だけに絞って
# RepoB.find を黙って落とす（docs/value-safety-qa.md の Q17）。別の小さなプロジェクトを設定を変えて解析する
# ---------------------------------------------------------------------------
NODF=work/nodf
mkdir -p "$NODF/src/nd"
cat > "$NODF/config.properties" <<'EOF'
project.root=.
source.folders=src
source.encoding=UTF-8
entry.packages=
dataflow.enabled=false
output.folder=./out
cache.folder=./.cache
EOF
for a in Bean Autowired Repository Component; do
    printf 'package nd;\n\n@interface %s { }\n' "$a" > "$NODF/src/nd/$a.java"
done
cat > "$NODF/src/nd/Svc.java" <<'EOF'
package nd;

interface Repo { void find(); }
@Repository class RepoA implements Repo { public void find() { System.out.println("a"); } }
class RepoB implements Repo { public void find() { System.out.println("b"); } }
class Cfg { @Bean Repo repoB() { return new RepoB(); } }

public class Svc {
    @Autowired private Repo repo;
    void run() { repo.find(); }
    void viaParam(Repo r) { r.find(); }
}
EOF
# 対照: @Bean メソッドの宣言した型に触れない呼び出しは、値を読まなくてもステレオタイプの Bean（MailA）に絞る
cat > "$NODF/src/nd/Notifier.java" <<'EOF'
package nd;

interface Mail { void send(); }
@Repository class MailA implements Mail { public void send() { System.out.println("a"); } }
class MailB implements Mail { public void send() { System.out.println("b"); } }

public class Notifier {
    @Autowired private Mail mail;
    void run() { mail.send(); }
}
EOF
# 値を読まない指定でも、Bean でないクラスの static メソッドの引数は注入点ではないので絞らない
# （レシーバがどの引数かは値の表にしか無いが、引数が注入点になるかは呼び出しを書いたメソッドで決まる）
cat > "$NODF/src/nd/Plain.java" <<'EOF'
package nd;

public class Plain {
    static void proc(Mail m) { m.send(); }
}
EOF
# 値を読まない指定でも、ソースが引数でない値を入れるフィールドは段 5 で絞らない（docs/spring-di-qa.md の Q15）。
# レシーバがどのフィールドかは値の表にしか無いので、呼び出しを書いた型（と親・外側の型）のフィールドのうち、型の当たるものに
# そういうフィールドがあれば絞らない。J 行の値の種別の列（N 行を読まずに分かる）から決める。39 版はどれも唯一の Bean に絞っていた
cat > "$NODF/src/nd/NdInit.java" <<'EOF'
package nd;

interface Ping { void ping(); }
class PingA implements Ping { public void ping() { System.out.println("a"); } }
@Repository class PingB implements Ping { public void ping() { System.out.println("b"); } }

public class NdInit {
    @Autowired private Ping p = new PingA();
    void run() { p.ping(); }
}
EOF
cat > "$NODF/src/nd/NdReset.java" <<'EOF'
package nd;

interface Pong { void pong(); }
class PongA implements Pong { public void pong() { System.out.println("a"); } }
@Repository class PongB implements Pong { public void pong() { System.out.println("b"); } }

@Component
public class NdReset {
    private Pong p;
    NdReset(Pong x) { this.p = x; }
    void reset() { this.p = new PongA(); }
    void run() { p.pong(); }
}
EOF
cat > "$NODF/src/nd/NdBase.java" <<'EOF'
package nd;

interface Pang { void pang(); }
class PangA implements Pang { public void pang() { System.out.println("a"); } }
@Repository class PangB implements Pang { public void pang() { System.out.println("b"); } }

public abstract class NdBase {
    @Autowired protected Pang p;
    void run() { p.pang(); }
}
EOF
cat > "$NODF/src/nd/NdSub.java" <<'EOF'
package nd;

public class NdSub extends NdBase {
    NdSub() { this.p = new PangA(); }
}
EOF
# 対照: 型の当たらないフィールド（StringBuilder）に new を入れていても、Mail のフィールドの呼び出しは絞る
cat > "$NODF/src/nd/NdMixed.java" <<'EOF'
package nd;

public class NdMixed {
    @Autowired private Mail mail;
    private final StringBuilder log = new StringBuilder();
    void run() { mail.send(); log.append("x"); }
}
EOF
( cd "$NODF" && "$JAVA_BIN" -cp "$CLASSES:$CP" jche.CallHierarchyExporter config.properties ) > "$NODF/run.log" 2>&1
NCSV=$(ls -d "$NODF"/out/*/ 2>/dev/null | sort | tail -1)call-hierarchy.csv
nd_rows() {   # $1=呼び出し元 Class.method  $2=呼び出し先 Class.method
    awk -F, -v c="at nd.$1(" -v d="$2" 'index($1, c) == 1 && $2 == d' "$NCSV" 2>/dev/null
}
if [ ! -f "$NCSV" ]; then
    ng "値を読まない指定: 解析できませんでした（test/pruning/$NODF/run.log）"
else
    for caller in Svc.run Svc.viaParam; do
        for callee in RepoA.find RepoB.find; do
            if [ -n "$(nd_rows "$caller" "$callee")" ]; then
                ok "値を読まない指定: $caller -> $callee（@Bean の Bean を数え、段 5 で RepoA に絞らない）"
            else
                ng "値を読まない指定: $caller -> $callee の行がありません"
                grep "^at nd.$caller(" "$NCSV" | head -2
            fi
        done
    done
    for callee in MailA.send MailB.send; do
        if [ -n "$(nd_rows Plain.proc "$callee")" ]; then
            ok "値を読まない指定: Plain.proc -> $callee（Bean でないクラスの static メソッドの引数は段 5 で絞らない）"
        else
            ng "値を読まない指定: Plain.proc -> $callee の行がありません"
            grep "^at nd.Plain.proc(" "$NCSV" | head -2
        fi
    done
    got=$(nd_rows Notifier.run MailA.send | head -1 | cut -d, -f3)
    if [ "$got" = RESOLVED:SPRING_DI ] && [ -z "$(nd_rows Notifier.run MailB.send)" ]; then
        ok "値を読まない指定: 対照 Notifier.run -> MailA.send は段 5 で絞る（RESOLVED:SPRING_DI）"
    else
        ng "値を読まない指定: 対照 Notifier.run -> MailA.send が段 5 で絞られていません（$got）"
    fi
    for c in "NdInit.run Ping.ping 初期化子（new PingA()）" "NdReset.run Pong.pong new の代入（reset）" \
             "NdBase.run Pang.pang 別のファイルの子クラスの this.p = new PangA()"; do
        read -r caller iface why <<< "$c"
        m=${iface#*.}
        for impl in "${iface%.*}A" "${iface%.*}B"; do
            if [ -n "$(nd_rows "$caller" "$impl.$m")" ]; then
                ok "値を読まない指定: $caller -> $impl.$m（$why のあるフィールドは段 5 で絞らない）"
            else
                ng "値を読まない指定: $caller -> $impl.$m の行がありません（$why のあるフィールドを段 5 で絞った）"
                grep "^at nd.$caller(" "$NCSV" | head -2
            fi
        done
    done
    got=$(nd_rows NdMixed.run MailA.send | head -1 | cut -d, -f3)
    if [ "$got" = RESOLVED:SPRING_DI ] && [ -z "$(nd_rows NdMixed.run MailB.send)" ]; then
        ok "値を読まない指定: 対照 NdMixed.run -> MailA.send は、型の当たらないフィールドの new に引きずられず段 5 で絞る"
    else
        ng "値を読まない指定: 対照 NdMixed.run -> MailA.send が段 5 で絞られていません（$got）"
    fi
fi

# ---------------------------------------------------------------------------
# 外部の jar からの被参照（external.library.folders）を、JVM が解決する宣言へ結びつける（JVMS 5.4.3.3）。
# 親インターフェースの宣言は、ほかの宣言の型の親型で宣言したものを除いた「最も特定的な」もので、private と static は
# 対象にならない。42 版までは親インターフェースを名前順の幅優先で辿った最初の宣言にしていたので、jar の
# new C().m() を上書きされた I1.m や static の ASI.m に結びつけ、実際に動く I2.m・Api.m の被参照を落としていた
# ---------------------------------------------------------------------------
EXTU=work/extu
mkdir -p "$EXTU/src/eu" "$EXTU/extsrc/ext" "$EXTU/extcls" "$EXTU/extjars"
cat > "$EXTU/config.properties" <<'EOF'
project.root=.
source.folders=src
source.encoding=UTF-8
entry.packages=
external.library.folders=extjars
output.folder=./out
cache.folder=./.cache
EOF
cat > "$EXTU/src/eu/Types.java" <<'EOF'
package eu;

public class Types { }
interface I1 { default void m() { System.out.println("I1"); } }
interface I2 extends I1 { default void m() { System.out.println("I2"); } }
interface ASI { static void s() { System.out.println("static"); } }
interface Api { default void s() { System.out.println("Api"); } }
EOF
cat > "$EXTU/src/eu/C.java" <<'EOF'
package eu;

public class C implements I1, I2 { }
EOF
cat > "$EXTU/src/eu/B2.java" <<'EOF'
package eu;

public class B2 implements I2 { }
EOF
cat > "$EXTU/src/eu/C2.java" <<'EOF'
package eu;

public class C2 extends B2 implements I1 { }
EOF
cat > "$EXTU/src/eu/C3.java" <<'EOF'
package eu;

public class C3 implements ASI, Api { }
EOF
cat > "$EXTU/extsrc/ext/Client.java" <<'EOF'
package ext;

public class Client {
    public static void callC() { new eu.C().m(); }
    public static void callC2() { new eu.C2().m(); }
    public static void callC3() { new eu.C3().s(); }
}
EOF
if "$JAVAC_BIN" -nowarn -encoding UTF-8 -d "$EXTU/extcls" $(find "$EXTU/src" "$EXTU/extsrc" -name '*.java') \
        > "$EXTU/javac.log" 2>&1 \
        && rm -rf "$EXTU/extcls/eu" \
        && "$(dirname "$JAVAC_BIN")/jar" --create --file "$EXTU/extjars/client.jar" -C "$EXTU/extcls" . ; then
    ( cd "$EXTU" && "$JAVA_BIN" -cp "$CLASSES:$CP" jche.CallHierarchyExporter config.properties ) > "$EXTU/run.log" 2>&1
fi
ECSV=$(ls -d "$EXTU"/out/*/ 2>/dev/null | sort | tail -1)call-hierarchy.csv
ext_rows() {   # $1=呼び出し元 Client.method  $2=呼び出し先 Class.method
    awk -F, -v c="at ext.$1(" -v d="$2" 'index($1, c) == 1 && $2 == d' "$ECSV" 2>/dev/null
}
if [ ! -f "$ECSV" ]; then
    ng "外部の jar からの被参照: 解析できませんでした（test/pruning/$EXTU/run.log・javac.log）"
else
    for c in "Client.callC I2.m I1.m C implements I1, I2" "Client.callC2 I2.m I1.m C2 extends B2(implements I2) implements I1" \
             "Client.callC3 Api.s ASI.s C3 implements ASI(static s), Api(default s)"; do
        read -r caller want wrong why <<< "$c"
        if [ -n "$(ext_rows "$caller" "$want")" ] && [ -z "$(ext_rows "$caller" "$wrong")" ]; then
            ok "外部の jar からの被参照: $caller -> $want（$why。$wrong ではない）"
        else
            ng "外部の jar からの被参照: $caller -> $want の行がありません（$why）"
            grep "^at ext.$caller(" "$ECSV" | head -3
        fi
    done
fi

[ $fail = 0 ] && echo "PASS" || echo "FAIL"
exit $fail
