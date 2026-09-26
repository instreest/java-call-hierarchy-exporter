package com.example.orderexport.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderexport.dao.OrderDao;
import com.example.orderexport.domain.Order;
import com.example.orderexport.exporter.OrderExporter;

/**
 * 期間内の受注を読み出し、呼び出し元から渡された形式で書き出す。
 * exporter の宣言型はインターフェースなので、ここだけを見てもどの実装が動くかは分からない。
 */
@Service
public class OrderExportService {

    private final OrderDao orderDao;

    public OrderExportService(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    @Transactional(readOnly = true)
    public String export(LocalDate from, LocalDate to, OrderExporter exporter) {
        List<Order> orders = orderDao.selectByOrderDate(from, to);
        return exporter.export(orders);
    }
}
