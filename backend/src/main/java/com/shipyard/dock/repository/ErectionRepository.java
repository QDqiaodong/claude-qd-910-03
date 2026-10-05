package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.shipyard.dock.domain.Erection;

import jakarta.persistence.LockModeType;

public interface ErectionRepository extends JpaRepository<Erection, Long> {

    List<Erection> findAllByOrderByIdAsc();

    boolean existsByErectionNo(String erectionNo);

    List<Erection> findByVesselIdOrderBySegSeqAsc(Long vesselId);

    Optional<Erection> findFirstByVesselIdOrderBySegSeqDesc(Long vesselId);

    List<Erection> findByBlockIdOrderByIdAsc(Long blockId);

    List<Erection> findByBerthIdOrderBySegSeqAsc(Long berthId);

    /** 排程在某台设备上、尚未开始吊装（可改派 / 待复核）的任务 */
    List<Erection> findByCraneIdAndStatusInOrderByPlanDateAscIdAsc(Long craneId, List<String> statuses);

    /** 待复核任务，恢复时逐个处理 */
    List<Erection> findByReviewPendingTrueOrderByPlanDateAscIdAsc();

    /** 行锁：同一任务的改派 / 吊次开始 / 恢复并发时串行化，配合 @Version 保证只有一个归属生效 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Erection e where e.id = :id")
    Optional<Erection> findByIdForUpdate(Long id);
}
