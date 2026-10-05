package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.Block;
import com.shipyard.dock.dto.BlockStatusStatDTO;

public interface BlockRepository extends JpaRepository<Block, Long> {

    List<Block> findAllByOrderByIdAsc();

    boolean existsByBlockCode(String blockCode);

    List<Block> findByVesselIdOrderBySegSeqAsc(Long vesselId);

    Optional<Block> findFirstByVesselIdOrderBySegSeqDesc(Long vesselId);

    @Query("select b from Block b where b.vesselId = :vesselId and b.status = :status order by b.segSeq")
    List<Block> findByVesselAndStatus(@Param("vesselId") Long vesselId, @Param("status") String status);

    /**
     * 手写 JPQL ②：按分段状态统计段数、总重量与平均重量。
     */
    @Query("select new com.shipyard.dock.dto.BlockStatusStatDTO("
            + "b.status, count(b), sum(b.weightT), avg(b.weightT)) "
            + "from Block b group by b.status order by count(b) desc")
    List<BlockStatusStatDTO> statByStatus();
}
