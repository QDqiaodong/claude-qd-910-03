package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.dto.ContractStatusStatDTO;

import jakarta.persistence.LockModeType;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    List<Contract> findAllByOrderByIdAsc();

    boolean existsByContractNo(String contractNo);

    List<Contract> findByVesselIdOrderByIdAsc(Long vesselId);

    /**
     * 合同状态推进 / 付款确认时的行级悲观锁：并发推进与并发确认串行化，
     * 防止重复结算或已结算金额被并发写坏。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Contract c where c.id = :id")
    Optional<Contract> findByIdForUpdate(@Param("id") Long id);

    /**
     * 手写 JPQL ⑤：按合同状态统计份数、金额合计与平均金额。
     */
    @Query("select new com.shipyard.dock.dto.ContractStatusStatDTO("
            + "c.status, count(c), sum(c.amount), avg(c.amount)) "
            + "from Contract c group by c.status order by count(c) desc")
    List<ContractStatusStatDTO> statByStatus();
}
