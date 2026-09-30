package jls.s08_04_08.a;

/**
 * JLS SE 26 §8.4.8: パッケージアクセスのメソッドは、同じパッケージのサブクラスにしか継承されない。
 *
 * {@code save(PkgUser)} はパッケージアクセスなので、別パッケージの {@code jls.s08_04_08.b.PkgUserRepo}
 * （{@code extends PkgRepoBase implements PkgRepo<PkgUser>}）には継承されず、{@code PkgRepo<T>.save(T)} を実装しない。
 * javac はブリッジを作らず、{@code PkgRepo<PkgUser> r = new PkgUserRepo(); r.save(u)} で動くのは default の
 * {@code PkgRepo.save}（Issue #168）。
 *
 * <p>同じパッケージのサブクラスに継承されても、public でないメソッドはインターフェースのメソッドを実装できない
 * （§8.4.8.3。javac は「弱いアクセス権限」のエラーにする）ので、インターフェースのメソッドを実装する継承したメソッドは
 * public のものに限る。
 */
public class PkgRepoBase {
    void save(PkgUser u) {
        PkgSink.fromClass();
    }
}
