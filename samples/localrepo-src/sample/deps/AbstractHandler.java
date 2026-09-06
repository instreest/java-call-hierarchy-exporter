package sample.deps;

/**
 * jar の中の基底クラス。ソース側の LogHandler はこれを継承する。
 * jar があるときだけ「LogHandler は Handler の実装」と分かり、Handler#handle の呼び出し先に解決される
 */
public abstract class AbstractHandler implements Handler {
    public void close() {
    }
}
