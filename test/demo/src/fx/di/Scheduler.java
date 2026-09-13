// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** @Bean で登録された SystemClock に解決される（SPRING_DI） */
@Service
public class Scheduler {

    @Autowired
    private Clock clock;

    public long tick() {
        return clock.now();
    }
}
