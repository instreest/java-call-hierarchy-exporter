package fx.generic;

/** 継承の軸と型引数の置換の軸が重なる形。本体を持つので、これ自身も候補になる */
public abstract class AbstractStore<T> {

    public void put(T item) {
        fallback();
    }

    void fallback() {
    }
}
