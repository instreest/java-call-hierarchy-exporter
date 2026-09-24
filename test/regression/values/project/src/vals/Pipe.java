package vals;

// V3 / V3c / V3e / V3k: 値が | を含む。
//   V3  parse2("x", "a|c") の経路の !mode.equals("a|b")
//       移す前: 実引数を | で切って "a" と読み、期待値も "a" に切るので「一致する」と見て notAb を
//               [UNREACHABLE]（= a）にする（誤り）。移した後: notAb はふつうの行
//   V3c parse2b("x", "a|b") の経路の同じ条件。移す前も後も [UNREACHABLE] だが、注記の値が
//       移す前は "a"（切った値）、移した後は "a|b"
//   V3e eq("a|b") の経路の m.equals("a|b")。移す前も後も成立する（hit はふつうの行）。
//       期待値だけを正確に読み、実引数を切ったままにすると誤って打ち切る
//   V3k 定数どうしの MODE.equals("a|b")。移す前も後も成立する（hitConst はふつうの行）
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
