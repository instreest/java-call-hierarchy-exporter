package fx.entry;

/**
 * 自前のフレームワークの「登録して後で呼ぶ」API（ライブラリ呼び出し規則で足す題材。#136 段階3）。
 * submit は溜めるだけで、run を呼ぶのは別の場所（この題材には無い）。
 * ライブラリ呼び出し規則 {@code fx.entry.Dispatcher#submit(java.lang.Runnable) -> a0 : run()} で繋ぐ。
 */
public class Dispatcher {

    private Runnable pending;

    public void submit(Runnable task) {
        this.pending = task;
    }
}
