package fx.entry;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/** フレームワーク（サーブレットコンテナ）が呼ぶ入口の題材（#136 段階2）。継承で判定する形 */
public class WebEntry extends HttpServlet {

    private final Dao dao = new OrderDaoImpl();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) {
        dao.findById(7L);
    }

    /** 上書きしていないメソッドは入口ではない */
    void helper() {
        dao.describe();
    }
}
