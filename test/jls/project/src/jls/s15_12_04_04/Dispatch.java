package jls.s15_12_04_04;

/**
 * JLS SE 26 §15.12.4.4 呼び出すメソッドを探す（実行時のクラスから、親クラスの連鎖を先に）。
 *
 * <ul>
 *   <li>{@code DTask extends DTaskBase implements Runnable} で {@code run()} を実装しているのは親クラスの
 *       {@code DTaskBase}。{@code Runnable} 型で呼んでも、{@code Thread} から呼び戻されても、動くのは
 *       {@code DTaskBase.run()}（§8.4.8 で DTask が継承した実装）。JDK のインターフェースのメソッド
 *       （ソースが無い）は、名前が先に並んでも（java.lang &lt; jls）実装として選ばれない</li>
 * </ul>
 */
public class Dispatch {
    void viaRunnable() {
        Runnable r = new DTask();
        r.run();
    }

    void viaClass() {
        new DTask().run();
    }

    void viaThread() {
        new Thread(new DTask()).start();
    }
}

class DTaskBase {
    public void run() {
        DSink.ran();
    }
}

class DTask extends DTaskBase implements Runnable {
}

class DSink {
    static void ran() {
    }
}
