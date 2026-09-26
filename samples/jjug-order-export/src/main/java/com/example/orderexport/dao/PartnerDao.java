package com.example.orderexport.dao;

import java.util.Optional;

import org.seasar.doma.Dao;
import org.seasar.doma.Select;
import org.seasar.doma.boot.ConfigAutowireable;

import com.example.orderexport.domain.Partner;

/**
 * 取引先マスタの DAO。実装クラス（PartnerDaoImpl）は Doma がコンパイル時に生成する。
 * SQL は META-INF/com/example/orderexport/dao/PartnerDao/selectById.sql
 */
@Dao
@ConfigAutowireable
public interface PartnerDao {

    @Select
    Optional<Partner> selectById(String partnerCode);
}
