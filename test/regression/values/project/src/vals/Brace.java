package vals;

// V4: 実引数の文字列が { を含む。
// 移す前: 1 つ目の実引数（"{"）の { から後ろを入れ子とみなして 2 つ目の実引数まで飲み込み、
//         1 つ目を別の値と読んで hitBrace を [UNREACHABLE] にする（誤り）。2 つ目は見えないので
//         braceMiss は判定しない。
// 移した後: hitBrace はふつうの行、braceMiss は [UNREACHABLE]（2 つ目の実引数 = k）
public class Brace {
    public static void main(String[] args) {
        brace("{", "k");
    }

    static void brace(String first, String second) {
        if ("{".equals(first)) {
            hitBrace();
        }
        if ("x".equals(second)) {
            braceMiss();
        }
    }

    static void hitBrace() {
        System.out.println("{");
    }

    static void braceMiss() {
        System.out.println("x");
    }
}
