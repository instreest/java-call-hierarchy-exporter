package pp;

/**
 * 列挙定数をキーに取るファクトリ。戻り値は 2 通りなのでデータフローでは決まらない。
 * config-mapping.properties では、キーを FACTORY_CONST の証拠として受け取った TypeMappingProvider が
 * mapping.properties の {@code FACTORY_CONST@pp.DaoKind.A} で ADao に絞る（CallResolver#hintKindOf）
 */
public class KindFactory {
    static Dao get(DaoKind k) {
        if (k == DaoKind.A) {
            return new ADao();
        }
        return new BDao();
    }
    public static void main(String[] args) { get(DaoKind.A).find(); }
}
