package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.CraneScheduleLog;

public interface CraneScheduleLogRepository extends JpaRepository<CraneScheduleLog, Long> {

    List<CraneScheduleLog> findAllByOrderByIdDesc();

    List<CraneScheduleLog> findByCraneIdOrderByIdDesc(Long craneId);

    List<CraneScheduleLog> findByErectionIdOrderByIdDesc(Long erectionId);

    List<CraneScheduleLog> findByLiftIdOrderByIdDesc(Long liftId);
}
