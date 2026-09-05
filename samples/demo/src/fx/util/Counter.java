package fx.util;

public class Counter {
    public int value;
    private int hidden;

    {
        hidden = initial();
    }

    public Counter() {}

    public Counter(int start) {
        this();
        value = start;
    }

    public void bump() {
        value++;
        value += 2;
        hidden = value;
        int v = this.value;
        System.out.println(v);
    }

    private int initial() { return 0; }

    public static int initialCount() { return 42; }

    public class Inner {
        public void touch() { bump(); }
    }

    public static class Nested {
        public void touch(Counter c) { c.bump(); }
    }
}
