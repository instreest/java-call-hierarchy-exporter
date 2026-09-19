package fx.entry;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;

import fx.dao.Dao;
import fx.dao.OrderDaoImpl;

/** フレームワークが呼ぶ入口の題材（#136 段階2）。アノテーションで判定する形 */
public class Jobs {

    private final Dao dao = new OrderDaoImpl();

    @Scheduled(cron = "0 0 * * * *")
    public void nightly() {
        dao.describe();
        internal();
    }

    @EventListener
    public void onEvent(Object event) {
        dao.findById(8L);
    }

    /** 内部からも呼ばれるが、フレームワークの入口でもある */
    @GetMapping("/list")
    public void list() {
        dao.describe();
    }

    /** 自前のフレームワークの入口。同梱の表には無いので、契約表を足したときだけ FRAMEWORK_ENTRY になる */
    @Endpoint
    public void custom() {
        new Dispatcher().submit(() -> dao.findById(9L));
    }

    private void internal() {
        list();
    }
}
