// V1: 無名パッケージの型の名前が K・V・L で始まると、経路の値（定数）と取り違えていた（stage B の B0a で直した）。
// check の引数には、make() が new した型の名前（Kind）が経路の値として入る。条件 k.equals("x") を判定するとき、
// 以前は種別の文字だけを見て Kind を「K: の値」と読み、':' が無いので値を空文字として比べて
// hitKind を [UNREACHABLE]（= ）にしていた。型の名前は値ではないので判定しない（hitKind はふつうの行）
public class Kind {
    public static void main(String[] args) {
        check((String) make());
    }

    static Object make() {
        return new Kind();
    }

    static void check(String k) {
        if (k.equals("x")) {
            hitKind();
        }
    }

    static void hitKind() {
        System.out.println("k");
    }
}
