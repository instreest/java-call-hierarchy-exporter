package pp;

import java.lang.reflect.Method;

/**
 * リフレクション。
 * <ul>
 *   <li>make(Derived.class) … Class を実引数で渡す（DataflowResolver#classOf の PARAM）</li>
 *   <li>type().newInstance() / make(type()) … Class を返すメソッド（classOf の RETURN と、
 *       valueOriginOf の RETURN が K: の値として次のフレームへ渡すこと）</li>
 *   <li>viaInvoke … Base.class の f を、Derived のインスタンスで invoke する（上書き先は
 *       レシーバの具象型で選ぶ。DataflowResolver#invokeTargets の recvType）</li>
 *   <li>spaced … 前に空白のあるクラス名。Class.forName は空白を取り除かないので実行すれば失敗する。
 *       文字列の値をそのまま型名として引き、解析対象に無い名前なので辺を張らない（DataflowResolver#literalOf）</li>
 * </ul>
 */
public class Refl {
    static Class<?> type() { return Derived.class; }
    @SuppressWarnings("deprecation")
    static void make(Class<?> c) throws Exception { c.newInstance(); }
    static void viaInvoke() throws Exception {
        Method m = Base.class.getMethod("f");
        m.invoke(new Derived());
    }
    static void spaced() throws Exception {
        Class.forName(" pp.Derived").getDeclaredConstructor().newInstance();
    }
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        make(Derived.class);
        type().newInstance();
        make(type());
        viaInvoke();
        spaced();
    }
}
