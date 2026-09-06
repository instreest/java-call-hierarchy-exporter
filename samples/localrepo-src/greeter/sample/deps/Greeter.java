package sample.deps;

/** sample.deps:greeter:1.0 の中の型。static ファクトリで生成し、戻り値を次の呼び出しに使わせる */
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

    /** 戻り値の型は core（greeter の依存）にある */
    public Formatter formatter() {
        return new Formatter();
    }
}
