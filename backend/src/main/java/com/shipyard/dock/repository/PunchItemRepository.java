package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.PunchItem;

public interface PunchItemRepository extends JpaRepository<PunchItem, Long> {

    List<PunchItem> findAllByOrderByIdAsc();

    boolean existsByPunchNo(String punchNo);

    List<PunchItem> findBySurveyIdOrderByIdAsc(Long surveyId);

    List<PunchItem> findByBlockIdOrderByIdAsc(Long blockId);

    /** 某分段尚未闭环的遗留项 */
    List<PunchItem> findByBlockIdAndStatusNotOrderByIdAsc(Long blockId, String status);

    long countByBlockIdAndStatusNot(Long blockId, String status);

    /** 某船全部分段下的遗留项（放行校验用） */
    @Query("select p from PunchItem p where p.blockId in "
            + "(select b.id from Block b where b.vesselId = :vesselId) order by p.id")
    List<PunchItem> findByVesselId(@Param("vesselId") Long vesselId);
}
