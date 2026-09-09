// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** Bean 登録されない実装 */
public class FixedClock implements Clock {

    @Override
    public long now() {
        return 1L;
    }
}
