package st;

import java.util.function.Supplier;

import st.util.Retry;

// S2: ラムダの本体の中の呼び出しは、別のファイルのクラス（Retry）が実行しても、
// caller 列は「ラムダを書いたメソッド」の「ラムダ本体の行」を指す。
public class Lambdas {

    static void work() {
    }

    static String name() {
        return "n";
    }

    // 別のファイルのクラスが実行する
    public static void viaOtherFile() {
        Retry.run(() -> {
            work();
        });
    }

    // 入れ子のラムダ
    public static void nested() {
        Retry.run(() -> {
            Retry.run(() -> {
                work();
            });
            name();
        });
    }

    // 同じファイルの別のメソッドが実行する
    static void runHere(Runnable r) {
        r.run();
    }

    public static void viaSameFile() {
        runHere(() -> work());
    }

    // 戻り値のあるラムダ
    public static String viaSupplier() {
        return Retry.call(() -> name());
    }

    // static フィールドと、フィールド初期化子のラムダ
    static final Supplier<String> NAMER = () -> name();

    final Runnable worker = () -> work();

    public static void useFields() {
        NAMER.get();
        new Lambdas().worker.run();
    }
}
