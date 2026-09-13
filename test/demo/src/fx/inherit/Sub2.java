package fx.inherit;

/** Base2#m を本体なしで再宣言する。これは実装ではないので候補に数えない */
public interface Sub2 extends Base2 {
    @Override
    void m();
}
