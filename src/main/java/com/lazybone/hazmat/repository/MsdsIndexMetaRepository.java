package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.MsdsIndexMeta;
import org.springframework.data.jpa.repository.JpaRepository;

/** 只有一行（id=1），不需要按字段查询 */
public interface MsdsIndexMetaRepository extends JpaRepository<MsdsIndexMeta, Integer> {
}
