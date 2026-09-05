package fx.reflect;

public class Target {
    static {
        System.out.println("init");
    }

    public void run() { step(); }
    public void run(long n) { step(); }
    public void run(String s, int n) { step(); }
    private void step() {}
    public Target() {}
    public Target(String name) { step(); }
}
