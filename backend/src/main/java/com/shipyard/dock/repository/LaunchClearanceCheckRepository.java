package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.LaunchClearanceCheck;

public interface LaunchClearanceCheckRepository extends JpaRepository<LaunchClearanceCheck, Long> {

    List<LaunchClearanceCheck> findByClearanceIdOrderByIdAsc(Long clearanceId);
}
