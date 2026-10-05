package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.ContractPaymentLog;

public interface ContractPaymentLogRepository extends JpaRepository<ContractPaymentLog, Long> {

    List<ContractPaymentLog> findByContractIdOrderByIdAsc(Long contractId);

    List<ContractPaymentLog> findByNodeIdOrderByIdAsc(Long nodeId);
}
