package fx.entry;

import org.junit.jupiter.api.Test;

/** テストランナーが呼ぶ入口の題材（#136 段階2） */
public class SampleTest {

    @Test
    void runsJob() {
        new Jobs().nightly();
    }
}
