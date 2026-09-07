package fx.model;

public enum Color implements Shape {
    RED("r") {
        @Override
        public double area() { return 1.0; }
    },
    BLUE("b");

    private final String code;

    Color(String code) {
        this.code = code;
        Registry.register(code);
    }

    @Override
    public double area() { return 0.0; }

    public String code() { return code; }
}
