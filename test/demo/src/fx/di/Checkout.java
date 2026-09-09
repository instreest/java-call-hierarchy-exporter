// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** フィールド注入。gateway.pay は Bean の CardPayment に解決される（SPRING_DI） */
@Service
public class Checkout {

    @Autowired
    private PaymentGateway gateway;

    public void checkout(int amount) {
        gateway.pay(amount);
    }
}
