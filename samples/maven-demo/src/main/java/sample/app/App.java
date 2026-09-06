package sample.app;

/** 起点。Service を通して依存 jar（sample.deps）の型を使う */
public class App {
    public static void main(String[] args) {
        new Service().run("world");
    }
}
