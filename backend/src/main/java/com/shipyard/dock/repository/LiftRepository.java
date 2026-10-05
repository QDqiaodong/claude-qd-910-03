package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.shipyard.dock.domain.Lift;
import com.shipyard.dock.dto.LiftLoadStatDTO;

import jakarta.persistence.LockModeType;

public interface LiftRepository extends JpaRepository<Lift, Long> {

    List<Lift> findAllByOrderByLiftTimeDesc();

    /** 排程页顺序：进行中置顶，其次未开始（按计划），最后历史吊次（按完成时间倒序） */
    @Query("select l from Lift l order by case l.status when 'IN_PROGRESS' then 0 "
            + "when 'SCHEDULED' then 1 else 2 end, l.liftTime desc")
    List<Lift> findAllForBoard();

    boolean existsByLiftNo(String liftNo);

    List<Lift> findByErectionIdOrderByLiftTimeAsc(Long erectionId);

    List<Lift> findByBlockIdOrderByLiftTimeAsc(Long blockId);

    List<Lift> findByCraneIdOrderByLiftTimeAsc(Long craneId);

    /** 设备上各状态吊次，检修汇总 / 负荷校验都用它 */
    List<Lift> findByCraneIdAndStatusInOrderByLiftTimeAsc(Long craneId, List<String> statuses);

    /** 未开始吊次（随排程改派） */
    List<Lift> findByErectionIdAndStatus(Long erectionId, String status);

    /** 实绩吊次（已完成历史吊次）数量；未开始 / 进行中属于排程与在制作业，不计入实绩统计 */
    long countByStatus(String status);

    /** 进行中吊次（归属冻结，禁止换机） */
    List<Lift> findByCraneIdAndStatusOrderByLiftTimeAsc(Long craneId, String status);

    /** 行锁：吊次开始 / 完成与检修改派互斥，杜绝「排程已改、吊次还挂旧机」 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lift l where l.id = :id")
    Optional<Lift> findByIdForUpdate(Long id);

    /**
     * 手写 JPQL ③：按起重设备类型统计吊次数、吊装总吨位与平均载荷率。
     * 只统计真实完成的历史吊次，未开始 / 进行中吊次不计入设备实绩负荷。
     */
    @Query("select new com.shipyard.dock.dto.LiftLoadStatDTO("
            + "c.craneType, count(l), sum(l.liftWeightT), avg(l.liftWeightT * 100.0 / c.ratedLoadT)) "
            + "from Lift l, Crane c where l.craneId = c.id and l.status = 'DONE' "
            + "group by c.craneType order by count(l) desc")
    List<LiftLoadStatDTO> statByCraneType();
}
