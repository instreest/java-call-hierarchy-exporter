package com.example.orderexport.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderexport.dao.OrderDao;
import com.example.orderexport.dao.PartnerDao;
import com.example.orderexport.domain.Order;
import com.example.orderexport.domain.Partner;
import com.example.orderexport.exporter.OrderExporter;
import com.example.orderexport.exporter.OrderExporterFactory;

/**
 * 期間内の受注を読み出し、指定の形式で書き出す。
 * exporter の宣言型はインターフェースなので、ここだけを見てもどの実装が動くかは分からない。
 */
@Service
public class OrderExportService {

    private final OrderDao orderDao;
    private final PartnerDao partnerDao;
    private final OrderExporterFactory orderExporterFactory;

    public OrderExportService(OrderDao orderDao, PartnerDao partnerDao, OrderExporterFactory orderExporterFactory) {
        this.orderDao = orderDao;
        this.partnerDao = partnerDao;
        this.orderExporterFactory = orderExporterFactory;
    }

    /** 呼び出し元から渡された形式で書き出す */
    @Transactional(readOnly = true)
    public String export(LocalDate from, LocalDate to, OrderExporter exporter) {
        List<Order> orders = orderDao.selectByOrderDate(from, to);
        return exporter.export(orders);
    }

    /** 取引先マスタに登録された連携形式で、取引先に渡すファイルを作る */
    @Transactional(readOnly = true)
    public ExportFile exportForPartner(String partnerCode, LocalDate from, LocalDate to) {
        Partner partner = partnerDao.selectById(partnerCode).orElseThrow();
        OrderExporter exporter = orderExporterFactory.get(partner.exportFormat());
        List<Order> orders = orderDao.selectByOrderDate(from, to);
        String fileName = "orders-" + partnerCode + "." + partner.exportFormat();
        return new ExportFile(fileName, exporter.export(orders));
    }
}
