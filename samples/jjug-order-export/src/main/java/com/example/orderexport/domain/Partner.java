package com.example.orderexport.domain;

import org.seasar.doma.Entity;
import org.seasar.doma.Id;
import org.seasar.doma.Table;

/** 取引先マスタ。partners テーブルの 1 行。連携ファイルの形式（json / xml）を取引先ごとに持つ */
@Entity
@Table(name = "partners")
public record Partner(
        @Id String partnerCode,
        String partnerName,
        String exportFormat) {
}
