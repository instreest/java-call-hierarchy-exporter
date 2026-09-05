package fx.excluded;

/** 除外パッケージ内の相互再帰（Ping.a → Pong.b → Ping.a） */
public class Ping {
    public static void a(int n) {
        if (n > 0) {
            Pong.b(n - 1);
        }
    }
}
