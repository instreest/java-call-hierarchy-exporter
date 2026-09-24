package jls.s15_27;

import java.util.function.Supplier;

/**
 * JLS SE 26 §15.27 ラムダ式。
 *
 * <ul>
 *   <li>§15.27.4: ラムダ本体の呼び出しは、ラムダが評価された場所ではなく、関数型インターフェースの
 *       メソッドが呼ばれたときに実行される。javac は本体を合成メソッド {@code lambda$...} にする</li>
 *   <li>入れ子のラムダ、インスタンス変数の初期化子の中のラムダ（§12.5 の通り、コンストラクタごとに評価）、
 *       クラス変数の初期化子の中のラムダ（§12.4.2 の通り、クラスの初期化で評価）</li>
 * </ul>
 * 合成メソッドの番号の振り方は javac の版で違い、JLS も決めていない
 * （{@code docs/lambda-expansion-qa.md} の Q13）。javac との突き合わせは、ラムダを囲む本物のメソッドと
 * 行で同一視して行う。
 */
public class Lambdas {
    static final Runnable STATIC_JOB = () -> Sink.staticJob();
    private final Runnable job = () -> Sink.instanceJob();

    Lambdas() {
    }

    Lambdas(int v) {
        Sink.use(v);
    }

    void run() {
        Runnable outer = () -> {
            Supplier<Integer> inner = () -> Sink.inner();
            inner.get();
        };
        outer.run();
        job.run();
        STATIC_JOB.run();
    }
}

class Sink {
    static void staticJob() {
    }

    static void instanceJob() {
    }

    static int inner() {
        return 1;
    }

    static void use(int v) {
    }
}
