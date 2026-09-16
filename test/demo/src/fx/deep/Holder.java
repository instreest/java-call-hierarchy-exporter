package fx.deep;

import fx.reflect.Target;

/** レシーバの入れ子を1段深くするための箱 */
public class Holder {
    public Object get() {
        return new Target();
    }
}
