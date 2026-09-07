package sample.app;

final class Util {
    static int calls;

    private Util() {
    }

    static void count(String text) {
        calls += text.isEmpty() ? 0 : 1;
    }
}
