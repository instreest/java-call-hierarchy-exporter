package st.util;

// S2: ラムダを実行する側のクラス。呼び出し元のラムダ本体はこのファイルとは別のファイルにあるので、
// 本体の中の呼び出しの caller 列に、このファイルの名前とラムダ本体の行番号を組み合わせてはいけない
// （ラムダ本体の行番号は Lambdas.java の行で、このファイルの行とは関係がない）。
public final class Retry {

    private Retry() {
    }

    public static void run(Runnable body) {
        for (int i = 0; i < 2; i++) {
            body.run();
        }
    }

    public static <T> T call(java.util.function.Supplier<T> body) {
        return body.get();
    }
}
