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
#       コンストラクタで受け取るフィールド・識別子の形の文字列を返すメソッド・契約表のキーになる戻り値）
#
# ケースを足すときは case_ を 1 回呼ぶ（ソースは標準入力。クラス 1 つで、main を起点にする）。
# 同じクラスの別の行も見るときは、続けて expect_ を呼ぶ。
# 起点は「呼び出し元が無いメソッド」（entry.packages が空）なので、ケースどうしは混ざらない。
# 契約表（work/contracts.txt）は KeyFactory と SepFactory の行だけで、それを使うケースにしか効かない。
#
# 期待の前に char: を付けたケースは「特性の記録（characterization）」で、今の読み手の誤った振る舞いを
# そのまま書いてある（値が出所の文字列の文法の文字 | ; { を含むと、読み手が値を途中で切って読み違える）。
# 値の読み手を文字列から値の表へ移すコミット（stage B の B3）で、char: を外して正しい期待に書き換える。
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
# ファクトリに渡したキーで絞る契約（KeyFactory・SepFactory を使うケースだけに効く）。
# SepFactory の行はキーが出所の文法の文字（| と ;）を含む。どちらの行に当たったかで、キーを切って読んだかが分かる
cat > work/contracts.txt <<'EOF'
pr.KeyFactory#get("A") => pr.DaoA
pr.SepFactory#get("USER") => pr.DaoA
pr.SepFactory#get("USER|X") => pr.DaoB
pr.SepFactory#get("S") => pr.DaoA
pr.SepFactory#get("S;T") => pr.DaoB
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

# ケースの一覧（解析の後で順に確かめる）。1 行 = 期待<TAB>呼び出し元<TAB>呼び出し先<TAB>説明
CASES=()

# case_ <期待> <クラス名> <呼び出し元 Class.method> <呼び出し先 Class.method> <説明>
#   ソース（package pr のクラス <クラス名>）を標準入力で渡す。期待は次のどれか:
#     reachable      その呼び出しの行があり、[UNREACHABLE] が付かない（打ち切ってはいけない）
#     pruned         その呼び出しの行があり、[UNREACHABLE] が付く（打ち切られるべき。対照）
#     listed         その呼び出しの行が候補として出る（絞り込みで落ちていない）
#     resolved:<種別> その行の resolved-by が <種別>（RESOLVED:LOCAL_NEW など。絞り込みが効く対照）
#     absent         その呼び出しの行が無い（絞り込みで別の実装に決まった対照）
#     char:<期待>    特性の記録（上の説明）。<期待> と同じに確かめ、OK の行に [特性] と付ける
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
# 値が出所の文字列の文法の文字（| ; {）を含む（特性の記録。stage B の B3 で char: を外して期待を直す）
# ---------------------------------------------------------------------------
case_ char:pruned SemiArg SemiArg.parse SemiArg.semicolon "特性: 実引数の \";\" を ; で切って空文字と読み、\";\".equals(delim) を打ち切る（B3 の後は reachable）" <<'EOF'
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

case_ char:pruned PipeArg PipeArg.parse2 PipeArg.notAb "特性: 実引数の \"a|c\" を | で切って \"a\" と読み、!mode.equals(\"a|b\") を打ち切る（B3 の後は reachable）" <<'EOF'
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

case_ char:pruned BraceArg BraceArg.brace BraceArg.hitBrace "特性: 実引数の \"{\" から後ろを入れ子とみなして次の実引数まで飲み込み、\"{\".equals(first) を打ち切る（B3 の後は reachable）" <<'EOF'
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
expect_ char:reachable BraceArg.brace BraceArg.braceMiss "特性: 飲み込まれた 2 つ目の実引数（\"k\"）は見えないので \"x\".equals(second) を判定しない（B3 の後は pruned）"

case_ char:resolved:RESOLVED:CONTRACT SepPipeKey SepPipeKey.run DaoA.find "特性: ファクトリのキー \"USER|X\" を | で切って契約表の get(\"USER\") に当てる（B3 の後は get(\"USER|X\") の DaoB）" <<'EOF'
package pr;

public class SepPipeKey {
    public static void main(String[] args) { run(); }
    static void run() { SepFactory.get("USER|X").find(); }
}
EOF
expect_ char:absent SepPipeKey.run DaoB.find "特性: 同上（DaoB の行が無い）"

case_ char:resolved:RESOLVED:CONTRACT SepSemiKey SepSemiKey.run DaoA.find "特性: ファクトリのキー \"S;T\" を ; で切って契約表の get(\"S\") に当てる（B3 の後は get(\"S;T\") の DaoB）" <<'EOF'
package pr;

public class SepSemiKey {
    public static void main(String[] args) { run(); }
    static void run() { SepFactory.get("S;T").find(); }
}
EOF
expect_ char:absent SepSemiKey.run DaoB.find "特性: 同上（DaoB の行が無い）"

case_ char:pruned HolderSemi HolderSemi.helper HolderSemi.hitHolder "特性: new の実引数 \"a;r=K:x.Y\" を ; で切り、コンストラクタで受け取るフィールドの値を \"a\" と読んで打ち切る（B3 の後は reachable）" <<'EOF'
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
    # 特性の記録（char:）は同じに確かめ、OK の行で分かるようにする
    if [ "${expect#char:}" != "$expect" ]; then
        expect=${expect#char:}
        label="[特性] $label"
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
        resolved:*)
            want=${expect#resolved:}
            got=$(head -1 <<< "$rows" | cut -d, -f3)
            [ "$got" = "$want" ] && ok "$label" \
                || { ng "$label（resolved-by が $got。期待は $want）"; echo "       $(head -1 <<< "$rows")"; } ;;
        *)
            ng "未知の期待 $expect（$label）" ;;
    esac
done

[ $fail = 0 ] && echo "PASS" || echo "FAIL"
exit $fail
