package vals;

// V3 / V3c / V3e / V3k: 値が | を含む。
//   V3  parse2("x", "a|c") の経路の !mode.equals("a|b")。期待: notAb はふつうの行。
//       以前の誤り: 実引数を | で切って "a" と読み、期待値も "a" に切るので「一致する」と見て notAb を
//               [UNREACHABLE]（= a）にしていた
//   V3c parse2b("x", "a|b") の経路の同じ条件。期待: [UNREACHABLE] で、注記の値は "a|b"
//       （以前は切った値 "a"）
//   V3e eq("a|b") の経路の m.equals("a|b")。成立する（hit はふつうの行）。
//       期待値だけを正確に読み、実引数を切ったままにすると誤って打ち切る（両方を切らずに読むことの確認）
//   V3k 定数どうしの MODE.equals("a|b")。成立する（hitConst はふつうの行）
public class Pipe {
    static final String MODE = "a|b";

    public static void main(String[] args) {
        parse2("x", "a|c");
    }

    public static void mainB() {
        parse2b("x", "a|b");
    }

    public static void mainEq() {
        eq("a|b");
    }

    public static void mainConst() {
        konst();
    }

    static void parse2(String s, String mode) {
        if (!mode.equals("a|b")) {
            notAb();
        }
    }

    static void parse2b(String s, String mode) {
        if (!mode.equals("a|b")) {
            notAbB();
        }
    }

    static void eq(String m) {
        if (m.equals("a|b")) {
            hit();
        }
    }

    static void konst() {
        if (MODE.equals("a|b")) {
            hitConst();
        }
    }

    static void notAb() {
        System.out.println("1");
    }

    static void notAbB() {
        System.out.println("2");
    }

    static void hit() {
        System.out.println("3");
    }

    static void hitConst() {
        System.out.println("4");
    }
}
