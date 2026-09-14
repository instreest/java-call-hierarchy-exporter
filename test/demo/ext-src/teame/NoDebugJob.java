package teame;

import fx.util.Counter;

/**
 * {@code javac -g:none} でコンパイルした class（extjars/team-e-lambda.jar）。
 * LineNumberTable も SourceFile も無いので、被参照スキャンは呼び出し元メソッドまでは出せるが
 * 行番号は出せない（caller が {@code (Unknown Source)} になる）ことの確認用。
 */
public class NoDebugJob {
    public void run() {
        Counter.initialCount();                      // static メソッド（EXACT）。行番号は出ない
    }
}
