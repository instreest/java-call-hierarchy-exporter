package fx.dao;

import org.seasar.doma.Dao;
import org.seasar.doma.Select;

/**
 * Doma の DAO インターフェース。実装（fx.dao.ItemDaoImpl）はコンパイル時に
 * アノテーションプロセッサが生成するので、ソースコードリポジトリには存在しない。
 * 呼び出し階層では「実装はコンパイル時生成（Doma）」の注記が付く。
 */
@Dao
public interface ItemDao {

    @Select
    Object selectById(long id);

    /** デフォルトメソッドは本体がソースにあるので、生成の対象ではない */
    default Object selectFirst() {
        return selectById(1L);
    }
}
