// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** 独自注釈で登録される実装。spring.di.bean.annotations=Audited のときだけ Bean とみなされる */
@Audited
public class AuditedLedger implements Ledger {

    @Override
    public void post(int amount) {
    }
}
