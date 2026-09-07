package fx.dao;

import missing.lib.LibDao;

/**
 * jar の基底クラス（LibDao implements Dao）を継承する実装。
 * 依存 jar があるときだけ Dao の実装として見える（回帰テスト jarchange 用）
 */
public class LibBackedDao extends LibDao {
    @Override
    public Object findById(long id) {
        return super.findById(id);
    }
}
