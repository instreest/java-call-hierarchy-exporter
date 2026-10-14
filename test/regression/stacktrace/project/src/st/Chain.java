package st;

// S1: 複数行にまたがる呼び出し連鎖。javac の行番号表は、連鎖の各呼び出しを「その呼び出しの行」にする
// （式の先頭の行ではない）。caller 列の行番号がこの行と一致することを見る。
public class Chain {

    private final StringBuilder log = new StringBuilder();

    Chain first() {
        log.append("first");
        return this;
    }

    Chain second() {
        return this;
    }

    Chain third() {
        return this;
    }

    static Chain make() {
        return new Chain();
    }

    public static void run() {
        make()
            .first()
            .second()
            .third();
        Chain.make().first(
        ).second();
        Chain
            .make();
    }
}
