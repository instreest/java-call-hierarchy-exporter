package teamw;

import fx.util.Counter;

/** ライブラリ呼び出し規則（Thread#start() が run() を呼ぶ）でだけ test/demo に届く形 */
public class AsyncJob implements Runnable {
    @Override
    public void run() {
        new Counter(1).bump();
    }
}
