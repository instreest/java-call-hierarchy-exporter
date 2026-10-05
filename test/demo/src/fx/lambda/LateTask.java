package fx.lambda;

/**
 * 呼び戻しの規則（{@code Thread#start()}）は呼び出し先に当たるのに、渡した値を追えない題材。
 * {@code task} は後から差し替えるフィールド（セッターで受け取るだけ）なので、出所が 1 つに定まらず
 * 具象型もラムダも分からない。以前は呼び戻しの辺を張らず、{@code Thread.start} 自身の行も
 * {@code exclude.packages}（{@code java.**}）で消えていたので、この呼び出しは出力のどこにも残らなかった。
 * 今は呼び出し先の行を除外に関わらず残し、{@code UNEXPANDED:CALLBACK} で「繋げなかった」ことを示す。
 */
public class LateTask {

    private Runnable task;

    public void setTask(Runnable task) {
        this.task = task;
    }

    /** 渡した値（フィールド）を追えないので、規則は当たるが繋がらない */
    public void kick() {
        new Thread(task).start();
    }
}
