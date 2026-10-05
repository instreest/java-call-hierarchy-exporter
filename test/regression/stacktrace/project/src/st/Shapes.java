package st;

// S3: 内部クラス・匿名クラス・ローカルクラスのバイナリ名（Outer$Inner / Outer$1 / Outer$1Local）と、
// ファイル名と違う名前のトップレベルクラス（Aux は Shapes.java にある）。
public class Shapes {

    static void leaf() {
    }

    static class Nested {
        void deep() {
            new Deeper().x();
        }

        class Deeper {
            void x() {
                leaf();
            }
        }
    }

    enum Kind {
        A {
            void go() {
                leaf();
            }
        };

        abstract void go();
    }

    public static void shapes() {
        Runnable anon = new Runnable() {
            @Override
            public void run() {
                leaf();
            }
        };
        class Local {
            void go() {
                leaf();
            }
        }
        new Local().go();
        anon.run();
        new Nested().deep();
        Kind.A.go();
        new Aux().secret();
    }
}

class Aux {
    void secret() {
        Shapes.leaf();
    }
}
