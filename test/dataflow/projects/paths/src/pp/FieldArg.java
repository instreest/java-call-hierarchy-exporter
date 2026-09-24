package pp;

/**
 * 注入の決まったフィールド（f = new CDao()）を、ファクトリの実引数に渡す。ファクトリ id は引数をそのまま
 * 返すので、pick() の戻り値の型はフィールドの型で決まる（DataflowBuilder#concreteTypeOf の FIELD）
 */
public class FieldArg {
    private final Dao f = new CDao();
    static Dao id(Dao d) { return d; }
    Dao pick() { return id(f); }
    public static void main(String[] args) { new FieldArg().pick().find(); }
}
