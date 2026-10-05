package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.dto.BerthUsageStatDTO;

import jakarta.persistence.LockModeType;

public interface BerthRepository extends JpaRepository<Berth, Long> {

    List<Berth> findAllByOrderByIdAsc();

    boolean existsByBerthNo(String berthNo);

    List<Berth> findByStatusOrderByIdAsc(String status);

    /** 某船当前占用的船台 */
    Optional<Berth> findFirstByVesselIdAndStatusOrderByIdAsc(Long vesselId, String status);

    /**
     * 确认放行释放船台时的行级悲观锁：与船台视图里的出坞 / 排船操作互斥，
     * 避免释放动作与手工改船位交叉导致状态不一致。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Berth b where b.id = :id")
    Optional<Berth> findByIdForUpdate(@Param("id") Long id);

    /** 空闲可排船的船台 */
    @Query("select b from Berth b where b.status = 'IDLE' order by b.berthNo")
    List<Berth> findIdle();

    /**
     * 手写 JPQL ①：按船台类型统计数量、占用数、平均长度与平均承载能力。
     */
    @Query("select new com.shipyard.dock.dto.BerthUsageStatDTO("
            + "b.berthType, count(b), sum(case when b.status = 'OCCUPIED' then 1 else 0 end), "
            + "avg(b.lengthM), avg(b.capacityT)) "
            + "from Berth b group by b.berthType order by count(b) desc")
    List<BerthUsageStatDTO> statByBerthType();
}
