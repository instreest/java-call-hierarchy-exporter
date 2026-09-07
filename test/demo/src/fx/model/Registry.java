package fx.model;

public final class Registry {
    private static final java.util.List<String> CODES = new java.util.ArrayList<>();

    static void register(String code) {
        CODES.add(code);
    }
}
