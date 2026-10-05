package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.VesselTypeStatDTO;

import jakarta.persistence.LockModeType;

public interface VesselRepository extends JpaRepository<Vessel, Long> {

    List<Vessel> findAllByOrderByIdAsc();

    boolean existsByVesselNo(String vesselNo);

    boolean existsByRegNo(String regNo);

    List<Vessel> findByOwnerIdOrderByIdAsc(Long ownerId);

    /**
     * 放行单生成 / 确认时的行级悲观锁：同一条船的生成与确认串行推进，
     * 防止并发下出现两张待确认放行单或重复下水。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vessel v where v.id = :id")
    Optional<Vessel> findByIdForUpdate(@Param("id") Long id);

    /**
     * 手写 JPQL ⑥：按船舶类型统计条数、平均船长与排水量合计。
     */
    @Query("select new com.shipyard.dock.dto.VesselTypeStatDTO("
            + "v.vesselType, count(v), avg(v.lengthM), sum(v.displacementT)) "
            + "from Vessel v group by v.vesselType order by count(v) desc")
    List<VesselTypeStatDTO> statByVesselType();
}
