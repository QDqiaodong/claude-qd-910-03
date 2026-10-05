package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.ContractPaymentNode;

import jakarta.persistence.LockModeType;

public interface ContractPaymentNodeRepository extends JpaRepository<ContractPaymentNode, Long> {

    List<ContractPaymentNode> findByContractIdOrderBySeqAsc(Long contractId);

    boolean existsByContractId(Long contractId);

    /**
     * 确认结算时的行级悲观锁：多人同时确认同一节点或网络重试时串行化，
     * 后到者在锁释放后读到「已结算」被拦下，全链路只有一个有效结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from ContractPaymentNode n where n.id = :id")
    Optional<ContractPaymentNode> findByIdForUpdate(@Param("id") Long id);
}
