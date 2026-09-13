// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

/** @Configuration の @Bean メソッドが登録する実装（型自身には注釈が無い） */
public class SystemClock implements Clock {

    @Override
    public long now() {
        return 0L;
    }
}
