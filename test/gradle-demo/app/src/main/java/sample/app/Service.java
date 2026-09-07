package sample.app;

import sample.deps.Greeter;
import sample.deps.Handler;
import sample.deps.util.Strings;

/**
 * 依存 jar の型を「戻り値で受けて次の呼び出しに使う」形で使う。
 * jar が無いと Greeter.of の戻り値の型が分からず、greet / formatter / wrap も型解決に失敗する。
 * handler は jar のインターフェース Handler 型。jar があれば Handler#handle への呼び出しとして
 * 解決される（jar が無いと型解決に失敗する）。実装のソース側の LogHandler までは辿らない
 * （jar で宣言されたインターフェースのメソッドは jar のメソッドとして出る。既存の仕様）。
 * Strings は util（版カタログ経由の依存）の型
 */
public class Service {
    private final Handler handler = new LogHandler();

    void run(String name) {
        var greeter = Greeter.of(name);
        String text = greeter.formatter().wrap(greeter.greet());
        handler.handle(Strings.upper(text));
        Util.count(text);
    }
}
