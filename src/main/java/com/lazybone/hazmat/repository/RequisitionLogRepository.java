package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.RequisitionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequisitionLogRepository extends JpaRepository<RequisitionLog, Long> {

    List<RequisitionLog> findByRequisitionIdOrderByCreatedAtAscIdAsc(Long requisitionId);
}
