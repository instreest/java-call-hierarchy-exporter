// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** 実装が 2 つあり、独自注釈 {@code @Audited} の方だけが Bean（既定の設定では両方とも Bean ではない） */
public interface Ledger {

    void post(int amount);
}
