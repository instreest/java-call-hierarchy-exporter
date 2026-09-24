package pp;

/**
 * 文字列のキーを取るファクトリに、キーを返すメソッド key() の戻り値を渡す（DataflowResolver#literalOf の
 * RETURN。return がどれも同じ文字列なら、その値がキーになる。戻り値の出所は、戻り値の型が String のような
 * final の型だと書かれないので Object で返す）。config-mapping.properties では
 * mapping.properties の {@code FACTORY_KEY@KEY_C} で CDao に絞る。
 * pick は return が 2 つとも同じ new CDao() で、戻り値の並びは同じ参照を 1 つにまとめる
 * （CallGraphBuilder#freezeValues。まとめても畳んだ結果は同じ）
 */
public class StrFactory {
    static Dao get(String key) {
        if ("KEY_C".equals(key)) {
            return new CDao();
        }
        return new BDao();
    }
    static Object key() { return "KEY_C"; }
    static Dao pick(boolean b) {
        if (b) {
            return new CDao();
        }
        return new CDao();
    }
    public static void main(String[] args) {
        get((String) key()).find();
        pick(args.length > 0).find();
    }
}
