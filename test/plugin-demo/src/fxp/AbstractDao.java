package fxp;

/** find() の本体を持つ親。継承した実装を拡張が指せるかの検査用（Issue #131） */
public abstract class AbstractDao implements Dao {
    @Override
    public void find() {
        loadCommon();
    }

    void loadCommon() {
    }
}
