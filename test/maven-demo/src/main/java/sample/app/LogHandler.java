package sample.app;

import sample.deps.AbstractHandler;

/** 依存 jar の基底クラス（AbstractHandler）を継承した Handler の実装。jar が無いと親が解決できず、エラーのあるファイルになる */
public class LogHandler extends AbstractHandler {
    @Override
    public void handle(String message) {
        Util.count(message);
    }
}
