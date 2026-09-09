// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Java Config。@Bean メソッドが返す具象型が Bean になる */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return new SystemClock();
    }
}
