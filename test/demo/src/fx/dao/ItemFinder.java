package fx.dao;

/** Doma の DAO を呼ぶ側。@Dao インターフェース経由の呼び出しの起点になる */
public class ItemFinder {

    private final ItemDao itemDao;

    public ItemFinder(ItemDao itemDao) {
        this.itemDao = itemDao;
    }

    public Object find(long id) {
        return itemDao.selectById(id);
    }

    public Object findFirst() {
        return itemDao.selectFirst();
    }
}
