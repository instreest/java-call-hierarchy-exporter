// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** Bean ではない実装。コンテナは注入しないので候補から外れる */
public class MockPayment implements PaymentGateway {

    @Override
    public void pay(int amount) {
    }
}
