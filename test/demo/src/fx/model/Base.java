package fx.model;

public class Base {
    protected Base(String name) {
        init(name);
    }

    protected void init(String name) {
        Registry.register(name);
    }
}
