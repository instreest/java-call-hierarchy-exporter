// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.stereotype.Component;

@Component("fastNotice")
public class FastNotice implements Notice {

    @Override
    public void send() {
    }
}
