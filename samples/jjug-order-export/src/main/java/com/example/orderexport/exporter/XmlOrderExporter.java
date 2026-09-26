package com.example.orderexport.exporter;

import java.io.StringWriter;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.springframework.stereotype.Component;

import com.example.orderexport.domain.Order;

/** 取引先連携向けの XML。取引先と取り決めた要素名・属性名で 1 件ずつ書き出す */
@Component
public class XmlOrderExporter implements OrderExporter {

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

    @Override
    public String export(List<Order> orders) {
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter xml = FACTORY.createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("orders");
            for (Order order : orders) {
                writeOrder(xml, order);
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("failed to write the orders as XML", e);
        }
        return out.toString();
    }

    private void writeOrder(XMLStreamWriter xml, Order order) throws XMLStreamException {
        xml.writeEmptyElement("order");
        xml.writeAttribute("id", String.valueOf(order.id()));
        xml.writeAttribute("customer", order.customerName());
        xml.writeAttribute("orderDate", order.orderDate().toString());
        xml.writeAttribute("amount", order.amount().toPlainString());
    }
}
