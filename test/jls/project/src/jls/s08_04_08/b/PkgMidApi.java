package jls.s08_04_08.b;

import jls.s08_04_08.a.PkgSink;

/**
 * JLS SE 26 §8.4.8: {@code jls.s08_04_08.a.PkgViaMid}（{@code extends PkgMid}）が実装するインターフェース。
 * 間の {@link PkgMid} が別パッケージなので {@code PkgRepoBase.save(PkgUser)} は継承されず、この default が動く。
 */
public interface PkgMidApi<T> {
    default void save(T t) {
        PkgSink.fromDefault();
    }
}
