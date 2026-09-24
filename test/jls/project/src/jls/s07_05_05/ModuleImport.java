package jls.s07_05_05;

import module java.base;

/**
 * JLS SE 26 §7.5.5 単一モジュールインポート宣言。
 *
 * {@code import module java.base;} は、そのモジュールが公開する全パッケージの公開型を
 * オンデマンドでインポートする。ここでは {@code List} と {@code Function} をその経路だけで解決する。
 * 型が解決できないと、呼び出し先のキー（引数型 {@code java.util.List}）が作れず呼び出しが落ちる。
 */
public class ModuleImport {
    public void run() {
        List<String> names = List.of("a");
        Function<String, Integer> len = String::length;
        Sink.use(names, len);
    }
}

class Sink {
    static void use(List<String> names, Function<String, Integer> fn) {
    }
}
