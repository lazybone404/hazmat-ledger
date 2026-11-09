package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.MsdsIndexLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface MsdsIndexLockRepository extends JpaRepository<MsdsIndexLock, Integer> {

    /**
     * 锁住固定行。等待超过数据库锁超时后抛异常，由调用方翻译成业务错误，
     * 不做无限等待——重建可能要几十秒，HTTP 请求不能跟着挂住。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from MsdsIndexLock l where l.id = 1")
    Optional<MsdsIndexLock> findForUpdate();
}
