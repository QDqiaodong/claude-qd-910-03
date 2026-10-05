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
     * 付款确认 / 联动重算时的行级悲观锁：与付款节点锁配合，
     * 串行化同一合同的结算动作，防止重复付款与合同已付金额错算。
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
