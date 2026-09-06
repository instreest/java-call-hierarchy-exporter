package sample.deps;

/** Greeter#formatter() の戻り値の型。jar が無いと Greeter の戻り値が分からず、この型のメソッド呼び出しも解決できない */
public class Formatter {
    public String wrap(String text) {
        return "[" + text + "]";
    }
}
