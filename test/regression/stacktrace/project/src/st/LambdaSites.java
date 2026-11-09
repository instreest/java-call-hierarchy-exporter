package st;

import st.util.Retry;

// S4: ラムダの本体の中の、型解決に失敗した呼び出し（UNRESOLVED の行）と、実装を 1 つに絞れない呼び出し
// （ライブラリ呼び出し規則のひな形 call-rules-suggested.txt に載る）も、caller 列とひな形の呼び出し箇所が
// ラムダを書いたメソッドとその行を指す（合成メソッドの名前 lambda$… を出さない）。
public class LambdaSites {

    interface Store {
        void save();
    }

    static class FileStore implements Store {
        @Override
        public void save() {
        }
    }

    static class DbStore implements Store {
        @Override
        public void save() {
        }
    }

    public static void unresolvedInside() {
        Retry.run(() -> {
            missing.Library.call();
        });
    }

    public static void candidatesInside(Store store) {
        Retry.run(() -> {
            store.save();
        });
    }
}
