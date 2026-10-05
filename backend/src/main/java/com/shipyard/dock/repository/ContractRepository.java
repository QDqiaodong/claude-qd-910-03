package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.dto.ContractStatusStatDTO;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    List<Contract> findAllByOrderByIdAsc();

    boolean existsByContractNo(String contractNo);

    List<Contract> findByVesselIdOrderByIdAsc(Long vesselId);

    /**
     * 手写 JPQL ⑤：按合同状态统计份数、金额合计与平均金额。
     */
    @Query("select new com.shipyard.dock.dto.ContractStatusStatDTO("
            + "c.status, count(c), sum(c.amount), avg(c.amount)) "
            + "from Contract c group by c.status order by count(c) desc")
    List<ContractStatusStatDTO> statByStatus();
}
