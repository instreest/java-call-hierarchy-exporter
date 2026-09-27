package jls.s15_09_05;

/**
 * JLS SE 26 §15.9.5.1 匿名コンストラクタ（親がジェネリックなクラスのとき）。
 *
 * 匿名コンストラクタは、生成式で選ばれた親のコンストラクタと同じ引数を取り、それをそのまま渡して親の
 * コンストラクタを呼ぶ。JDT は匿名コンストラクタの引数に、型引数を置き換えた型（{@code Holder<Item>} の
 * {@code Holder(T)} なら {@code Item}、{@code Arr<String>} の {@code Arr(T[])} なら {@code String[]}）を与える。
 * 親のコンストラクタの宣言の形（{@code T} は {@code Object} に消去される）と比べると一致せず、辺が落ちるか、
 * 引数なしのコンストラクタへ向いていた。
 * <ul>
 *   <li>{@code typeVar}: {@code Holder(T)} と {@code Holder()} がある親。{@code Holder(T)} を呼ぶ</li>
 *   <li>{@code array}: {@code Arr(T[])}</li>
 *   <li>{@code varargs}: {@code Va(T...)} と {@code Va()}。可変長引数の {@code Va(T...)} を呼ぶ</li>
 *   <li>{@code diamond}: ダイヤモンド {@code new Holder<>(i) { }}（§15.9.3。型引数は推論される）</li>
 * </ul>
 * ジェネリックなコンストラクタ（{@code <X> Gen(X)}）と、ジェネリックな外側のクラスの内部クラスを親にするものは
 * test/ctorbody/run.sh で見る。javac は匿名コンストラクタの引数を消去した型（{@code Object}）や外側のインスタンスを
 * 含む並びで作り、JDT の並び（{@code Item}）と匿名コンストラクタ自身のキーが食い違うので、ここの突き合わせに載せられない
 */
public class GenericSuper {
    Object typeVar(Item i) {
        return new Holder<Item>(i) { };
    }

    Object array(String[] a) {
        return new Arr<String>(a) { };
    }

    Object varargs() {
        return new Va<String>("a", "b") { };
    }

    Object diamond(Item i) {
        return new Holder<>(i) { };
    }
}

class Item {
}

abstract class Holder<T> {
    Holder() {
        GSink.holderNoArg();
    }

    Holder(T t) {
        GSink.holderValue();
    }
}

abstract class Arr<T> {
    Arr(T[] values) {
        GSink.array();
    }
}

abstract class Va<T> {
    Va() {
        GSink.vaNoArg();
    }

    @SafeVarargs
    Va(T... values) {
        GSink.vaValues();
    }
}

class GSink {
    static void holderNoArg() {
    }

    static void holderValue() {
    }

    static void array() {
    }

    static void vaNoArg() {
    }

    static void vaValues() {
    }
}
