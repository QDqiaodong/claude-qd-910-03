package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.BerthMoveLog;

public interface BerthMoveLogRepository extends JpaRepository<BerthMoveLog, Long> {

    /** 船台平面占位图上的最近移泊记录（新的在前） */
    List<BerthMoveLog> findTop20ByOrderByIdDesc();

    /** 某船的全部移泊记录（新的在前），船舶详情页追溯用 */
    List<BerthMoveLog> findByVesselIdOrderByIdDesc(Long vesselId);
}
