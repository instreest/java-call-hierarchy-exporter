// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** 実装を持つ抽象基底。Bean はこれを継承するだけでオーバーライドしない */
public abstract class AbstractPayment implements PaymentGateway {

    @Override
    public void pay(int amount) {
        record(amount);
    }

    void record(int amount) {
    }
}
