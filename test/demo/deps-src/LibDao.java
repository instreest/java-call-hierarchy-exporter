package missing.lib;

import fx.dao.Dao;

/**
 * ソース側のインターフェース fx.dao.Dao を実装する「jar の中の基底クラス」。
 * fx.dao.LibBackedDao がこれを継承する。jar が無いと LibBackedDao が Dao の実装であることが
 * 分からず、Dao#findById の CHA 候補に入らない（jar を足すと候補が 1 件増える）
 */
public abstract class LibDao implements Dao {
    @Override
    public Object findById(long id) {
        return null;
    }

    @Override
    public String describe() {
        return "lib";
    }
}
