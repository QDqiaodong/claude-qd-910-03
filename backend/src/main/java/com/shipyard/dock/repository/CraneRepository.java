package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.shipyard.dock.domain.Crane;

import jakarta.persistence.LockModeType;

public interface CraneRepository extends JpaRepository<Crane, Long> {

    List<Crane> findAllByOrderByIdAsc();

    boolean existsByCraneNo(String craneNo);

    @Query("select c from Crane c where c.status = 'AVAILABLE' order by c.ratedLoadT desc")
    List<Crane> findAvailable();

    /** 行锁：与检修、改派、吊次开始等事务互斥，防止并发下负荷 / 状态读到旧值 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Crane c where c.id = :id")
    Optional<Crane> findByIdForUpdate(Long id);
}
