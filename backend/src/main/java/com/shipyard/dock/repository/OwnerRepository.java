package com.shipyard.dock.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shipyard.dock.domain.Owner;

public interface OwnerRepository extends JpaRepository<Owner, Long> {

    List<Owner> findAllByOrderByIdAsc();

    boolean existsByOwnerNo(String ownerNo);
}
