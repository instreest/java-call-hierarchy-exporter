package sample.multi.app;

import sample.deps.Greeter;
import sample.deps.util.Strings;
import sample.multi.core.Core;

/**
 * app モジュール。兄弟モジュール core（ソースから解決）、core 経由の推移的な依存 greeter
 * （app の pom.xml には書いていない）、親の dependencyManagement で版が決まる util を使う
 */
public class Main {
    public static void main(String[] args) {
        String text = Core.hello("world");
        String loud = Strings.upper(text);
        Greeter.of(loud).formatter().wrap(loud);
    }
}
