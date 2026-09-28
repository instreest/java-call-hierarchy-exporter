package jls.s08_04_08.b;

import jls.s08_04_08.a.PkgRepoBase;

/**
 * JLS SE 26 §8.4.8: 別パッケージの {@link PkgRepoBase} を継ぐ中間のクラス。パッケージアクセスの
 * {@code save(PkgUser)} はこのクラスのメンバーにならないので、これを継ぐ {@code jls.s08_04_08.a.PkgViaMid}
 * （{@code PkgRepoBase} と同じパッケージ）にも継承されない。
 */
public class PkgMid extends PkgRepoBase {
}
