// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** 実装が複数あり、CHA だけでは絞れないインターフェース */
public interface PaymentGateway {

    void pay(int amount);
}
