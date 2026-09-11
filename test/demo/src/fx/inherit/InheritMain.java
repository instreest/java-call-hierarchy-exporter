package fx.inherit;

/** 起点。引数で受けた宣言型に対して呼ぶので、CHA の候補がそのまま行に出る */
public class InheritMain {
    public static void run(Greeter g, Base2 b) {
        g.greet();   // 候補: BaseGreeter.greet（PoliteGreeter が継承）と LoudGreeter.greet の 2 件
        b.m();       // 候補: OnlyImpl.m の 1 件（SINGLE_IMPL）
    }
}
