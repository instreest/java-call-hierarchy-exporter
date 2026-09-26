package vals;

// B0b で直した: getMethod の引数型に obj.getClass() を渡し、obj がコンストラクタで受け取ったフィールドのとき。
// フィールドの出所は経路の値（コンストラクタの実引数 "x" の L:x）をそのまま返しうるので、以前はそれを
// クラスの名前として引数型の並びに入れ（take(L:x)）、そんなメソッドは無いので解決をやめて呼び出しを
// 宣言のまま（jar の Method#invoke）にしていた。型でない値はクラスとみなさない（引数型が分からないので、
// 名前が一致するメソッドを候補にする）。run → take がリフレクションで繋がる
public class Refl {
    private final Object p;

    public Refl(Object p) {
        this.p = p;
    }

    public void run(String name) throws Exception {
        Refl.class.getMethod(name, p.getClass()).invoke(this, p);
    }

    public void take(String s) {
        System.out.println(s);
    }

    public static void main(String[] args) throws Exception {
        new Refl("x").run("take");
    }
}
