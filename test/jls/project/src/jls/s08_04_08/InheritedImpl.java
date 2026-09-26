package jls.s08_04_08;

/**
 * JLS SE 26 §8.4.8.1 親クラスから継承したメソッドによるインターフェースのメソッドの実装（型引数の置換つき）。
 *
 * <ul>
 *   <li>§8.4.8.1: クラス C が親クラスから継承したインスタンスメソッド mC は、C の親インターフェースのメソッド mI の
 *       シグネチャ（C から見た、型引数を置き換えたもの）の subsignature なら、C から見て mI を上書きする。
 *       {@code IiUserRepo extends IiBase implements IiRepo<IiUser>} の {@code IiBase.save(IiUser)} は
 *       {@code IiRepo.save(T)} を実装し、javac は IiUserRepo に {@code save(Object)} のブリッジを作る。
 *       消去した引数型が違う（{@code save(IiUser)} と {@code save(Object)}）うえ、IiBase 自身は IiRepo を実装しないので、
 *       IiBase.save の宣言には上書きの関係が無い（IiUserRepo から見たときだけの関係）</li>
 *   <li>親クラスの側が型引数を持つ形（{@code IiStrImpl extends IiGen<String> implements IiStr} の
 *       {@code IiGen.put(T)} が {@code IiStr.put(String)} を実装する）も同じ</li>
 *   <li>§8.4.8: 実装がクラスのメソッドなので、親インターフェースの default より勝つ（{@code IiMk} の
 *       {@code make(String)} は {@code IiMaker.make} で、{@code IiFac.make} は動かない）。同じ IiMaker を継承しても、
 *       {@code IiOther extends IiMaker implements IiFac<Integer>} では {@code make(String)} が {@code make(Integer)} の
 *       subsignature でないので実装にならず、default の {@code IiFac.make} が動く（型ごとの関係）</li>
 * </ul>
 * 上書きの関係を宣言ごと（O 行）にしか持たないと、IiRepo.save の実装が見つからず「実装なし」になったり、
 * default に決めたりして、動く IiBase.save・IiGen.put・IiMaker.make が呼び出し元なしになる。
 */
public class InheritedImpl {
    static void viaRepo(IiRepo<IiUser> r) {
        r.save(new IiUser());
    }

    static void viaStr(IiStr s) {
        s.put("x");
    }

    static void mk() {
        IiFac<String> f = new IiMk();
        f.make("x");
    }

    static void other() {
        IiFac<Integer> f = new IiOther();
        f.make(1);
    }
}

class IiUser {
}

interface IiRepo<T> {
    void save(T t);
}

class IiBase {
    public void save(IiUser u) {
        IiSink.saved();
    }
}

class IiUserRepo extends IiBase implements IiRepo<IiUser> {
}

class IiGen<T> {
    public void put(T t) {
        IiSink.put();
    }
}

interface IiStr {
    void put(String s);
}

class IiStrImpl extends IiGen<String> implements IiStr {
}

interface IiFac<T> {
    default void make(T t) {
        IiSink.fromDefault();
    }
}

class IiMaker {
    public void make(String s) {
        IiSink.fromClass();
    }
}

class IiMk extends IiMaker implements IiFac<String> {
}

class IiOther extends IiMaker implements IiFac<Integer> {
}

class IiSink {
    static void saved() {
    }

    static void put() {
    }

    static void fromDefault() {
    }

    static void fromClass() {
    }
}
