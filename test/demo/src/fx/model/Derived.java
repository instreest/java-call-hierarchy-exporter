package fx.model;

/** 明示的な super(...) 呼び出し。親コンストラクタの中の呼び出しへ辿れる */
public class Derived extends Base {
    public Derived() {
        super("derived");
    }
}
