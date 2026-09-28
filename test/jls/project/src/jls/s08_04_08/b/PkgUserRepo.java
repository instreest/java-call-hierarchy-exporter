package jls.s08_04_08.b;

import jls.s08_04_08.a.PkgRepoBase;
import jls.s08_04_08.a.PkgUser;

/**
 * JLS SE 26 §8.4.8: 別パッケージの親クラス {@link PkgRepoBase} のパッケージアクセスの {@code save(PkgUser)} は
 * 継承されないので、{@code PkgRepo<PkgUser>.save(T)} を実装するものは無く、default の {@code PkgRepo.save} が動く
 * （javac はブリッジを作らない。Issue #168）。
 */
public class PkgUserRepo extends PkgRepoBase implements PkgRepo<PkgUser> {
}
