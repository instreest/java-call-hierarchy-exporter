package pp;

/**
 * 親のフィールド（Parent#dao）と子のフィールド（own）に、コンストラクタで別の具象型を渡す。
 * 子のコンストラクタの実引数で解決してよいのは子のフィールドだけ（DataflowResolver#fieldTypeOf の
 * 「フィールドの宣言元が、実引数を束縛したコンストラクタの型か」の確認）。mine の dao.find() を ADao に
 * 取り違えると、実際に呼ばれる BDao#find が黙って消える
 */
public class Child extends Parent {
    private final Dao own;
    public Child(Dao own, Dao forParent) { super(forParent); this.own = own; }
    public void mine() { own.find(); dao.find(); }
    public static void main(String[] args) {
        new Child(new ADao(), new BDao()).mine();
        new Child(new ADao(), new BDao()).go();
    }
}
