package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.Requisition;
import com.lazybone.hazmat.entity.RequisitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 申请单。
 *
 * 列表条件（申请人、状态、时间范围）按需组合，因此用 Specification；
 * 按申请人/状态的固定方法保留给内部统计与测试，对外列表接口不再使用。
 */
public interface RequisitionRepository extends JpaRepository<Requisition, Long>,
        JpaSpecificationExecutor<Requisition> {

    Optional<Requisition> findByReqNo(String reqNo);

    List<Requisition> findByApplicantOrderByCreatedAtDesc(String applicant);

    List<Requisition> findByStatusOrderByCreatedAtDesc(RequisitionStatus status);

    long countByStatus(RequisitionStatus status);
}
