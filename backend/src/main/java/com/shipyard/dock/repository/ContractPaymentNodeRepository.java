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

    Optional<ContractPaymentNode> findFirstByContractIdAndStatusOrderBySeqAsc(Long contractId, String status);

    long countByContractIdAndStatus(Long contractId, String status);

    /**
     * 确认付款时的行级悲观锁：多人同时确认 / 网络重试同一节点时在此串行，
     * 后到者读到「已付款」后被业务校验拦下，保证只有一笔有效结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from ContractPaymentNode n where n.id = :id")
    Optional<ContractPaymentNode> findByIdForUpdate(@Param("id") Long id);
}
