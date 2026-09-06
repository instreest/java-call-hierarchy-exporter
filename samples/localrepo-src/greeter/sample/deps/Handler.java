package sample.deps;

/** ソース側（sample.app.LogHandler）が実装するインターフェース */
public interface Handler {
    void handle(String message);
}
