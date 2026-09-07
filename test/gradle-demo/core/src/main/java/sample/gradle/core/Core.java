package sample.gradle.core;

import sample.deps.Greeter;

/** core プロジェクト。app からは project(':core') で参照される（app のテストではソースは解析対象にしない） */
public class Core {
    public static String hello(String name) {
        return Greeter.of(name).greet();
    }
}
