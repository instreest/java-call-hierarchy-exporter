package sample.deps;

/**
 * sample.deps:core:1.0 の中の型。greeter が依存する（推移的な依存）。
 * Greeter#formatter() の戻り値の型なので、core の jar が無いと wrap の呼び出しは型解決に失敗する
 */
public class Formatter {
    public String wrap(String text) {
        return "[" + text + "]";
    }
}
