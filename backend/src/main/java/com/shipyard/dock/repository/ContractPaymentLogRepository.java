package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.ContractPaymentLog;

public interface ContractPaymentLogRepository extends JpaRepository<ContractPaymentLog, Long> {

    /** 某合同的全部操作记录（只追加，按时间正序展示证据链） */
    List<ContractPaymentLog> findByContractIdOrderByIdAsc(Long contractId);

    /** 某节点的全部操作记录（节点详情的历史追溯） */
    List<ContractPaymentLog> findByNodeIdOrderByIdAsc(Long nodeId);
}
