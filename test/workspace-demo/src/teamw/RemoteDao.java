package teamw;

import fx.dao.Dao;

/** test/demo のインターフェースの実装がワークスペースの他のプロジェクトにある形（CHA の候補に入る） */
public class RemoteDao implements Dao {
    @Override
    public Object findById(long id) {
        Helper.log("remote " + id);
        return null;
    }

    @Override
    public String describe() {
        return "remote";
    }
}
