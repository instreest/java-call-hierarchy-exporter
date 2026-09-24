package pp;

/**
 * クラス名を受け取ってリフレクションで作るファクトリ（戻り値の出所は C:0）に、別のファクトリ adao() が
 * クラス名の文字列を渡して委譲する。adao() の戻り値の型は経路によらず ADao に決まる
 * （DataflowBuilder#applyInvocationArgs の REFLECT。解析対象にある型名だけを採る）。
 * none() は解析対象に無い型名を渡すので決まらない
 */
public class ReflFactory {
    static Dao byName(String className) throws Exception {
        return (Dao) Class.forName(className).getDeclaredConstructor().newInstance();
    }
    static Dao adao() throws Exception { return byName("pp.ADao"); }
    static Dao none() throws Exception { return byName("pp.NoSuchDao"); }
    public static void main(String[] args) throws Exception {
        adao().find();
        none().find();
    }
}
