package jls.s08_04_08;

/**
 * JLS SE 26 §8.4.8 継承（クラスのメソッドはインターフェースの default メソッドより勝つ）。
 *
 * <ul>
 *   <li>§8.4.8: クラス C は、親クラスから継承した具象メソッドと同じシグネチャの default メソッドを
 *       親インターフェースから継承しない。{@code CwImpl extends CwMid implements CwApi} で {@code m()} を呼ぶと、
 *       2 段上の {@code CwBase.m()} が動き、1 段上の {@code CwApi.m()} は動かない（JVMS 5.4.6 も親クラスの
 *       連鎖を先に見る）。インターフェースの名前が親クラスより先に並ぶ（CwApi &lt; CwBase）形にしてある</li>
 *   <li>同じ深さでも同じ（{@code CwOrder extends CwService implements CwAuditable}。CwAuditable &lt; CwService）</li>
 *   <li>§8.4.8 / §8.2: private メソッドは継承されないので、親クラスの private の {@code CwHidden.m()} は
 *       {@code CwPrivImpl} のメソッドではなく、動くのは親インターフェースの default の {@code CwApi.m()}</li>
 * </ul>
 * 親型を名前順に混ぜて辿ると、どれも動かない宣言に行き、動く宣言が「呼び出し元なし」になる。
 */
public class ClassWins {
    void viaClass() {
        new CwImpl().m();
    }

    void viaInterface(CwApi api) {
        api.m();
    }

    void viaAuditable(CwAuditable a) {
        a.audit();
    }

    void viaPrivate(CwApi2 x) {
        x.m();
    }
}

class CwBase {
    public void m() {
        CwSink.base();
    }
}

class CwMid extends CwBase {
}

interface CwApi {
    default void m() {
        CwSink.api();
    }
}

interface CwApi2 extends CwApi {
}

class CwImpl extends CwMid implements CwApi {
}

class CwService {
    public void audit() {
        CwSink.service();
    }
}

interface CwAuditable {
    default void audit() {
        CwSink.auditable();
    }
}

class CwOrder extends CwService implements CwAuditable {
}

class CwHidden {
    private void m() {
        CwSink.hidden();
    }

    void use() {
        m();
    }
}

class CwPrivImpl extends CwHidden implements CwApi2 {
}

class CwSink {
    static void base() {
    }

    static void api() {
    }

    static void service() {
    }

    static void auditable() {
    }

    static void hidden() {
    }
}
