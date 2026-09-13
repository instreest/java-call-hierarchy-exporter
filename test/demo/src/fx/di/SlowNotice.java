// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.stereotype.Component;

@Component("slowNotice")
public class SlowNotice implements Notice {

    @Override
    public void send() {
    }
}
