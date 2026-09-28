package jls.s08_04_08.b;

import jls.s08_04_08.a.PkgSink;

/**
 * JLS SE 26 §8.4.8: {@link PkgUserRepo} が実装するインターフェース。{@code PkgUserRepo} は別パッケージの
 * パッケージアクセスの {@code jls.s08_04_08.a.PkgRepoBase.save(PkgUser)} を継承しないので、
 * {@code save(T)} を実装するものは無く、この default が動く（Issue #168）。
 */
public interface PkgRepo<T> {
    default void save(T t) {
        PkgSink.fromDefault();
    }
}
