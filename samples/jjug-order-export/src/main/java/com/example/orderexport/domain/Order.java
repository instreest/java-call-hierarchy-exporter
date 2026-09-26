package com.example.orderexport.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.seasar.doma.Entity;
import org.seasar.doma.Id;
import org.seasar.doma.Table;

/** 受注。orders テーブルの 1 行 */
@Entity
@Table(name = "orders")
public record Order(
        @Id Long id,
        String customerName,
        LocalDate orderDate,
        BigDecimal amount) {
}
