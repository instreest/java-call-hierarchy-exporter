package jls.s08_04_08.b;

import jls.s08_04_08.a.PkgUser;
import jls.s08_04_08.a.PkgViaMid;

/**
 * JLS SE 26 §8.4.8 継承（パッケージアクセスのメソッドは、同じパッケージのサブクラスにしか継承されない）。
 *
 * <ul>
 *   <li>§8.4.8: {@code PkgUserRepo extends a.PkgRepoBase implements PkgRepo<a.PkgUser>} で、
 *       {@code a.PkgRepoBase.save(PkgUser)} はパッケージアクセスなので別パッケージの {@code PkgUserRepo} に継承されず、
 *       {@code PkgRepo<PkgUser>.save(T)} を実装しない（§8.4.8.1 の「継承したメソッドによる実装」は、継承されたメソッドにだけ
 *       成り立つ）。{@code r.save(u)} で動くのは default の {@code PkgRepo.save}。javac はブリッジを作らない（Issue #168）</li>
 *   <li>間に別パッケージのクラスが挟まる形（{@code a.PkgViaMid extends b.PkgMid extends a.PkgRepoBase}）は、自分が
 *       {@code PkgRepoBase} と同じパッケージでも継承されない（{@code PkgMid} のメンバーでないため）。default が動く</li>
 *   <li>同じパッケージのサブクラスに継承される形は、§8.4.8.3（弱いアクセス権限）でコンパイルできない。
 *       public な継承したメソッドが実装になる形は {@code jls.s08_04_08.InheritedImpl}</li>
 * </ul>
 * 継承されないメソッドを「継承した実装」（H 行の 8 列目）に書くと、読み手は親インターフェースより先にそれを選び、
 * 実際に動く default が出力から消え、動かない {@code PkgRepoBase.save} が確定として出る。
 */
public class PkgInherited {
    static void viaRepo(PkgRepo<PkgUser> r) {
        r.save(new PkgUser());
    }

    static void direct() {
        PkgRepo<PkgUser> r = new PkgUserRepo();
        r.save(new PkgUser());
    }

    static void viaMid(PkgMidApi<PkgUser> r) {
        r.save(new PkgUser());
    }

    static void use() {
        viaRepo(new PkgUserRepo());
        viaMid(new PkgViaMid());
    }
}
