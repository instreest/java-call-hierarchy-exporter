package fx.generic;

import java.util.function.Consumer;

/** 消去形と同じシグネチャの実装（比較対象。キーの照合だけでも契約が当たる側） */
public class RawPrinter implements Consumer<Object> {

    @Override
    public void accept(Object value) {
        rawPrinted();
    }

    void rawPrinted() {
    }
}
