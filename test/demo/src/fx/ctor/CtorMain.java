package fx.ctor;

/** 起点。3 つの書き方のどれからも CtorBase.prepare へ辿れること */
public class CtorMain {
    public static void run() {
        new ImplicitSuper();
        new DefaultCtor();
        new ExplicitSuper();
    }
}
