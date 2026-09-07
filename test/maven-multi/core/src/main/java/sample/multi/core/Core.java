package sample.multi.core;

import sample.deps.Greeter;

/** core モジュール。依存 jar greeter を使う */
public class Core {
    public static String hello(String name) {
        return Greeter.of(name).greet();
    }
}
