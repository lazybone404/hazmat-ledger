package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.ScrapOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * 报废单。
 *
 * <p>列表条件（申请人、状态）按需组合，因此用 Specification；单号是唯一的业务主键，
 * 用点查而不是「先查全表再过滤」。
 */
public interface ScrapOrderRepository extends JpaRepository<ScrapOrder, Long>,
        JpaSpecificationExecutor<ScrapOrder> {

    Optional<ScrapOrder> findByScrapNo(String scrapNo);
}
