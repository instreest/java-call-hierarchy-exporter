package com.example.orderexport.dao;

import java.time.LocalDate;
import java.util.List;

import org.seasar.doma.Dao;
import org.seasar.doma.Select;
import org.seasar.doma.boot.ConfigAutowireable;

import com.example.orderexport.domain.Order;

/**
 * 受注の DAO。実装クラス（OrderDaoImpl）は Doma がコンパイル時に生成するので、ソースには無い。
 * SQL は META-INF/com/example/orderexport/dao/OrderDao/selectByOrderDate.sql
 */
@Dao
@ConfigAutowireable
public interface OrderDao {

    @Select
    List<Order> selectByOrderDate(LocalDate from, LocalDate to);
}
