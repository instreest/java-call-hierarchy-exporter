package fx.inherit;

/** Base2#m の唯一の実装。Sub2 の再宣言を候補に数えると SINGLE_IMPL にならず CHA（候補2件）になっていた */
public class OnlyImpl implements Sub2 {
    @Override
    public void m() {
    }
}
