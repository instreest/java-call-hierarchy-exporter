package jls.s09_04_01;

/**
 * JLS SE 26 §9.4.1 インターフェースのメソッドの継承と上書き。
 *
 * <ul>
 *   <li>§9.4.1.1: {@code MsI2 extends MsI1} の {@code MsI2.m()} は {@code MsI1.m()} を上書きする。
 *       {@code MsC implements MsI1, MsI2} も {@code MsC2 extends MsB2 implements MsI1}（MsB2 implements MsI2。
 *       MsI1 のほうが近い）も、動くのは最も特定的な {@code MsI2.m()}（JVMS 5.4.6 の maximally-specific）。
 *       呼び出し先（JDT のバインディング）が {@code MsI1.m()} でも同じ</li>
 *   <li>§9.4.1 / §8.4.8: インターフェースの static メソッドは継承されない。{@code MsC3 implements MsAaStatic, MsApi}
 *       の {@code m()} は {@code MsApi.m()} で、名前が先に並ぶ {@code MsAaStatic.m()} ではない</li>
 *   <li>§9.4.1.1（抽象の再宣言）: {@code MsAdI2 extends MsAdI1} が抽象のまま {@code m()} を再宣言し、
 *       {@code abstract class MsAdC implements MsAdI1, MsAdI2} で MsAdI1 が先に並ぶ。{@code MsAdC} の型で呼んだ
 *       {@code m()} の宣言は maximally-specific の {@code MsAdI2.m()}（幅優先で先に当たる {@code MsAdI1.m()} ではない。
 *       JVMS 5.4.3.3。検査の側（{@code JlsCheck#resolve}）が幅優先の最初の一致を採っていた形。Issue #187）で、
 *       動くのは部分型 {@code MsAdD.m()}</li>
 * </ul>
 */
public class MostSpecific {
    void viaSub(MsI2 x) {
        x.m();
    }

    void viaSuper(MsI1 x) {
        x.m();
    }

    void viaNewC() {
        new MsC().m();
    }

    void viaNewC2() {
        new MsC2().m();
    }

    void viaStatic(MsApi x) {
        x.m();
    }

    void viaNewC3() {
        new MsC3().m();
    }

    void viaAbstractDiamond(MsAdC x) {
        x.m();
    }
}

interface MsAdI1 {
    void m();
}

interface MsAdI2 extends MsAdI1 {
    @Override
    void m();
}

abstract class MsAdC implements MsAdI1, MsAdI2 {
}

class MsAdD extends MsAdC {
    @Override
    public void m() {
        MsSink.ad();
    }
}

interface MsI1 {
    default void m() {
        MsSink.i1();
    }
}

interface MsI2 extends MsI1 {
    @Override
    default void m() {
        MsSink.i2();
    }
}

class MsC implements MsI1, MsI2 {
}

class MsB2 implements MsI2 {
}

class MsC2 extends MsB2 implements MsI1 {
}

interface MsAaStatic {
    static void m() {
        MsSink.staticM();
    }
}

interface MsApi {
    default void m() {
        MsSink.api();
    }
}

class MsC3 implements MsAaStatic, MsApi {
}

class MsSink {
    static void i1() {
    }

    static void i2() {
    }

    static void staticM() {
    }

    static void api() {
    }

    static void ad() {
    }
}
