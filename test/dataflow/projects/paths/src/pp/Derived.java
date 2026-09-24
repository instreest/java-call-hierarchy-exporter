package pp;

/** Refl の題材（Base#f を上書きする。レシーバの具象型で上書き先を選ぶかを見る） */
public class Derived extends Base {
    public Derived() { }
    @Override public void f() { System.out.println("derived"); }
}
