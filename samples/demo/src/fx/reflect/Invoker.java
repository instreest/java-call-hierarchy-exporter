package fx.reflect;

import java.lang.reflect.Method;

public class Invoker {
    private static final String CLASS_NAME = "fx.reflect.Target";
    private static final String METHOD = "run";

    public void byLiteral() throws Exception {
        Class.forName("fx.reflect.Target").getMethod("run", long.class).invoke(new Target(), 1L);
    }

    public void byConstant() throws Exception {
        Class<?> c = Class.forName(CLASS_NAME);
        Method m = c.getMethod(METHOD);
        m.invoke(c.getDeclaredConstructor().newInstance());
    }

    public void byClassLiteral() throws Exception {
        Target.class.getMethod("run", String.class, int.class).invoke(new Target(), "a", 1);
    }

    public void byGetClass() throws Exception {
        Target t = new Target("x");
        t.getClass().getMethod("run").invoke(t);
    }

    public void nameOnly(Class<?>[] types) throws Exception {
        Target.class.getMethod("run", types).invoke(new Target());
    }

    public void viaParam() throws Exception {
        invokeNamed("fx.reflect.Target", "run");
        create("fx.reflect.Target");
        Class.forName(classOf()).getConstructor(String.class).newInstance("n");
    }

    private void invokeNamed(String cls, String name) throws Exception {
        Class.forName(cls).getMethod(name, long.class).invoke(null, 2L);
    }

    private Object create(String cls) throws Exception {
        return Class.forName(cls).newInstance();
    }

    private String classOf() {
        return CLASS_NAME;
    }

    public void unknown(String fromConfig) throws Exception {
        Class.forName(fromConfig).getMethod("run").invoke(null);
    }
}
