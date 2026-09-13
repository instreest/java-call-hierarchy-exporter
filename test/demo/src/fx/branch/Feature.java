package fx.branch;

/**
 * 変数値と条件分岐の静的解析（branch.pruning.enabled）の確認用。
 *
 * 呼び出し元から渡された値で成立しないと分かる条件の中の呼び出しは、
 * その1行だけ理由付きで出力され、そこから先は辿られない。
 */
public class Feature {

    /** 列挙定数は「値」として扱うので、switch と == の判定に使える */
    public enum Mode { FULL, LIGHT, NONE }

    /** コンパイル時定数。false なので dump() の中身はどの経路でも辿らない */
    private static final boolean DEBUG = false;

    public void run(boolean verbose) {
        if (verbose) {
            report();
        } else {
            summary();
        }
        if (DEBUG) {
            dump();
        }
    }

    public void mode(String name) {
        if ("full".equals(name)) {
            full();
        } else if ("light".equals(name)) {
            light();
        }
    }

    public void pick(int kind) {
        switch (kind) {
            case 1 -> one();
            case 2 -> two();
            default -> other();
        }
    }

    public void select(Mode mode) {
        switch (mode) {
            case FULL -> full();
            case LIGHT, NONE -> light();
        }
        if (mode == Mode.FULL) {
            report();
        }
    }

    void report() {
        trace("report");
    }

    void summary() {
        trace("summary");
    }

    /** dump() からしか呼ばれない。打ち切りで階層CSVから丸ごと消える */
    void dump() {
        dumpDetail();
    }

    /** dump() の先にしか無いメソッド（methods.csv の absentCause で拾える） */
    void dumpDetail() {
        trace("dump");
    }

    void full() {
        trace("full");
    }

    void light() {
        trace("light");
    }

    void one() {
        trace("one");
    }

    void two() {
        trace("two");
    }

    void other() {
        trace("other");
    }

    void trace(String label) {
        System.out.println(label);
    }
}
