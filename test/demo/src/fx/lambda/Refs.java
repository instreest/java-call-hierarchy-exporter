package fx.lambda;

import java.util.function.Consumer;
import java.util.function.Supplier;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;
import fx.dao.UserDaoImpl;

/**
 * メソッド参照の参照先が仮想メソッドのときに、どこへ繋ぐかを試すための題材（JLS 15.13.3）。
 * <ul>
 *   <li>{@link #viaBoundRef} … レシーバを束縛した参照（{@code dao::describe}）。レシーバの
 *       具象型（{@code OrderDaoImpl}）が分かるので、その実装に確定する</li>
 *   <li>{@link #viaUnboundRef} … 型名で書いた参照（{@code Dao::describe}）。レシーバは呼び出し時の
 *       第1引数で追っていないので、上書き候補（CHA）を全部出す。宣言 {@code Dao.describe} を
 *       本体の無い葉として「確定」と書いてはいけない</li>
 *   <li>{@link #viaExprBody} / {@link #viaBlockBody} … ラムダの戻り値。式本体とブロック本体で
 *       同じ結果になること（JLS 15.27.2。式本体は {@code return 式;} と同じ）</li>
 * </ul>
 */
public class Refs {

    private final Dao dao = new OrderDaoImpl();

    public void viaBoundRef() {
        Runnable r = dao::describe;
        r.run();
    }

    public void viaUnboundRef() {
        Consumer<Dao> c = Dao::describe;
        c.accept(new UserDaoImpl());
    }

    public void viaExprBody() {
        Supplier<Dao> s = () -> new UserDaoImpl();
        s.get().describe();
    }

    public void viaBlockBody() {
        Supplier<Dao> s = () -> {
            return new UserDaoImpl();
        };
        s.get().describe();
    }
}
