package fx.deep;

import fx.dao.Dao;
import fx.dao.DaoFactory;

/**
 * 値の追跡の上限が外れたことを確かめるための題材（{@code docs/cache-split-qa.md} の Q21）。
 *
 * どの形も、上限があった頃は追えずに CHA 止まりになっていた。
 */
public class Deep {

    /**
     * 実引数の入れ子。
     *
     * 旧: 実引数の出所は1段で剥がしていたので、{@code byName} に渡した
     * クラス名が見えず、{@code use} の引数の具象型が決まらなかった
     */
    public void nestedArgument() throws Exception {
        use(DaoFactory.byName("fx.dao.OrderDaoImpl"));
    }

    private void use(Dao dao) {
        dao.describe();
    }

    /**
     * レシーバの入れ子が4段（invoke ← getMethod ← getClass ← get）。
     *
     * 旧: 3段で打ち切っていたので、いちばん内側の {@code get()} が見えず、
     * getClass の対象が決まらなかった
     */
    public void deepReceiver() throws Exception {
        new Holder().get().getClass().getMethod("run").invoke(new Holder().get());
    }

    /**
     * 64 文字を超えるクラス名の文字列。
     *
     * 旧: 文字列リテラルは64文字以内のものだけ拾っていたので、この名前は落ちていた
     */
    public void longClassName() throws Exception {
        Class.forName("fx.deep.longnamed.AnExtremelyLongTargetClassNameForTestingTheLengthLimit")
                .getMethod("ping").invoke(null);
    }
}
