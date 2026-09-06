package sample.deps;

/** サンプルの依存 jar（sample.deps:greeter:1.0）の中の型。static ファクトリで生成し、戻り値を次の呼び出しに使わせる */
public class Greeter {
    private final String name;

    private Greeter(String name) {
        this.name = name;
    }

    public static Greeter of(String name) {
        return new Greeter(name);
    }

    public String greet() {
        return "Hello, " + name;
    }

    public Formatter formatter() {
        return new Formatter();
    }
}
