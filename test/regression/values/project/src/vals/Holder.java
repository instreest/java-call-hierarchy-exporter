package vals;

// V7: コンストラクタで受け取るフィールドに、出所の文法に似た文字列（"a;r=K:x.Y"）が入る。
// 期待: hitHolder はふつうの行（コンストラクタの実引数からフィールドを経て値が渡る道は残したまま、値をそのまま比べる）。
// 以前の誤り: new の実引数を ; で切って "a" と読み、フィールド v の値を "a" として helper に渡すので、
//         hitHolder を [UNREACHABLE]（= a）にしていた
public class Holder {
    private final String v;

    public Holder(String v) {
        this.v = v;
    }

    public void run() {
        helper(v);
    }

    static void helper(String s) {
        if (s.equals("a;r=K:x.Y")) {
            hitHolder();
        }
    }

    static void hitHolder() {
        System.out.println("h");
    }

    public static void main(String[] args) {
        new Holder("a;r=K:x.Y").run();
    }
}
