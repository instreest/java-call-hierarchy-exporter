package vals;

// V2 / V2c: 実引数の文字列が ; を含む。
// 期待: main の経路の semicolon はふつうの行。
//       other の経路（","）は [UNREACHABLE]（= ,）。
// 以前の誤り: main の経路で parse の 2 つ目の実引数（";"）を ; で切って空文字と読み、semicolon を
//         [UNREACHABLE]（= ）にしていた
public class Delim {
    public static void main(String[] args) {
        parse("x", ";");
    }

    public static void other() {
        parse("x", ",");
    }

    static void parse(String s, String delim) {
        if (";".equals(delim)) {
            semicolon();
        }
    }

    static void semicolon() {
        System.out.println(";");
    }
}
