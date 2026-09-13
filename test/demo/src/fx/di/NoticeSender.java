// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package fx.di;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Bean が2つあるので、@Qualifier のBean名で1つに定まる（SPRING_DI_QUALIFIER） */
@Service
public class NoticeSender {

    @Autowired
    @Qualifier("slowNotice")
    private Notice notice;

    public void notifyUser() {
        notice.send();
    }
}
