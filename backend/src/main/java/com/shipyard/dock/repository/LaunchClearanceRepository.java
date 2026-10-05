package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.LaunchClearance;

import jakarta.persistence.LockModeType;

public interface LaunchClearanceRepository extends JpaRepository<LaunchClearance, Long> {

    List<LaunchClearance> findAllByOrderByIdDesc();

    List<LaunchClearance> findByVesselIdOrderByIdDesc(Long vesselId);

    Optional<LaunchClearance> findFirstByVesselIdAndStatusOrderByIdDesc(Long vesselId, String status);

    boolean existsByVesselIdAndStatus(Long vesselId, String status);

    /** 作废重开的反向追溯：找出回引了指定单号的新单 */
    Optional<LaunchClearance> findFirstByPreviousNoOrderByIdDesc(String previousNo);

    /**
     * 确认 / 签认 / 复核 / 作废时的行级悲观锁：
     * 并发操作同一放行单时串行排队，后到者读到最新状态后被业务校验拦下。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LaunchClearance c where c.id = :id")
    Optional<LaunchClearance> findByIdForUpdate(@Param("id") Long id);
}
