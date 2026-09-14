package teame;

import fx.util.Counter;

/**
 * ラムダ式とメソッド参照から自プロジェクトのメソッドを参照する「他チームの jar」。
 * extjars/team-e-lambda.jar に入っている。どちらもバイトコードでは invokedynamic になり、
 * 参照先は BootstrapMethods 属性の引数（MethodHandle）にしか現れない。
 * 被参照スキャンがそこまで辿って、呼び出し元メソッドと行番号を出せることの確認用。
 */
public class LambdaJob {
    public void run() {
        Counter c = new Counter(5);                  // EXACT（コンストラクタ）
        Runnable byRef = c::bump;                    // メソッド参照 → Counter.bump（この行）
        Runnable byLambda = () -> c.bump();          // ラムダ本体の呼び出し → 囲みメソッド run に計上（この行）
        byRef.run();
        byLambda.run();
    }
}
