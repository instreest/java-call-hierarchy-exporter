// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * フィールド注入。既定では Ledger の実装のどちらも Bean ではないので CHA の 2 候補のまま、
 * spring.di.bean.annotations=Audited のときは AuditedLedger に解決される（SPRING_DI）
 */
@Service
public class Billing {

    @Autowired
    private Ledger ledger;

    public void bill(int amount) {
        ledger.post(amount);
    }
}
