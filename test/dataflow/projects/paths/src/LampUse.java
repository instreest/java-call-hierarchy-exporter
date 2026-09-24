/**
 * Lamp（無名パッケージ）を引数で渡し、その引数をファクトリの実引数に渡す。ファクトリのキー
 * （FactoryCalls#keysOf）は、経路の引数が型なら読まない
 */
public class LampUse {
    static pp.Dao make(Object key) {
        return new pp.ADao();
    }
    static void use(Object k) {
        make(k).find();
    }
    public static void main(String[] args) {
        use(new Lamp());
    }
}
