package pp;

/**
 * switch の case に値が 2 つ（IN）。3 を渡した経路では case 1, 2 の hit() に届かない
 * （GuardEvaluator の IN が成立しないこと）。default の other() には届く
 */
public class InGuard {
    static void check(int k) {
        switch (k) {
            case 1, 2 -> hit();
            default -> other();
        }
    }
    static void hit() { System.out.println("hit"); }
    static void other() { System.out.println("other"); }
    public static void main(String[] args) { check(3); }
}
