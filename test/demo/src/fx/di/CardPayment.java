// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.stereotype.Service;

/** Bean として登録される実装（pay は基底クラスから継承する） */
@Service
public class CardPayment extends AbstractPayment {

    @Override
    void record(int amount) {
    }
}
