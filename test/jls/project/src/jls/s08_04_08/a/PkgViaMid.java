package jls.s08_04_08.a;

import jls.s08_04_08.b.PkgMid;
import jls.s08_04_08.b.PkgMidApi;

/**
 * JLS SE 26 §8.4.8: 自分は {@link PkgRepoBase} と同じパッケージでも、間に別パッケージの {@link PkgMid} が挟まると、
 * パッケージアクセスの {@code save(PkgUser)} は {@code PkgMid} のメンバーにならず、ここにも継承されない。
 * {@code PkgMidApi<PkgUser>.save(T)} を実装するものは無く、動くのは default の {@code PkgMidApi.save}
 * （javac はブリッジを作らない）。
 */
public class PkgViaMid extends PkgMid implements PkgMidApi<PkgUser> {
}
