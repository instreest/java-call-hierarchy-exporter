package com.example.orderexport.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.orderexport.exporter.XmlOrderExporter;
import com.example.orderexport.service.OrderExportService;

/** 取引先システムとの連携口。受注を取り決めた形式の XML で返す */
@RestController
public class PartnerOrderController {

    private final OrderExportService orderExportService;

    public PartnerOrderController(OrderExportService orderExportService) {
        this.orderExportService = orderExportService;
    }

    @GetMapping(value = "/partners/{partnerCode}/orders", produces = MediaType.APPLICATION_XML_VALUE)
    public String orders(
            @PathVariable String partnerCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return orderExportService.export(from, to, new XmlOrderExporter(partnerCode));
    }
}
