package vals;

// V2 / V2c: 実引数の文字列が ; を含む。
// 移す前: main の経路で parse の 2 つ目の実引数（";"）を ; で切って空文字と読み、semicolon を
//         [UNREACHABLE]（= ）にする（誤り）。
// 移した後: semicolon はふつうの行。
// other の経路（","）は、移す前も後も正しく [UNREACHABLE]（= ,）
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
