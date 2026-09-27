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
}
