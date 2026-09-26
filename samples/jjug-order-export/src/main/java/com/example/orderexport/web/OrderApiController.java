package com.example.orderexport.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.orderexport.exporter.JsonOrderExporter;
import com.example.orderexport.service.OrderExportService;

/** 画面（SPA）向けの API。受注を JSON で返す */
@RestController
public class OrderApiController {

    private final OrderExportService orderExportService;
    private final JsonOrderExporter jsonOrderExporter;

    public OrderApiController(OrderExportService orderExportService, JsonOrderExporter jsonOrderExporter) {
        this.orderExportService = orderExportService;
        this.jsonOrderExporter = jsonOrderExporter;
    }

    @GetMapping(value = "/api/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public String orders(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return orderExportService.export(from, to, jsonOrderExporter);
    }
}
