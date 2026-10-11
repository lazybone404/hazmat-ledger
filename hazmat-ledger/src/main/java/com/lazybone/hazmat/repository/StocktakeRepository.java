package com.lazybone.hazmat.repository;

import com.lazybone.hazmat.entity.Stocktake;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface StocktakeRepository
        extends JpaRepository<Stocktake, Long>, JpaSpecificationExecutor<Stocktake> {

    Optional<Stocktake> findByStocktakeNo(String stocktakeNo);
}
