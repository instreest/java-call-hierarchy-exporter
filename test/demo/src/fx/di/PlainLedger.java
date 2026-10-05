// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** 注釈の無い実装。Bean ではないので、独自注釈を Bean の印にしたときは候補から外れる */
public class PlainLedger implements Ledger {

    @Override
    public void post(int amount) {
    }
}
