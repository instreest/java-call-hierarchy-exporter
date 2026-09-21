package fx.generic;

/** 消去後のキーと同じ実装。キーの照合だけでも引ける側（比較対象） */
public class RawRepo implements Repo<Object> {

    @Override
    public void save(Object item) {
        logged();
    }

    void logged() {
    }
}
