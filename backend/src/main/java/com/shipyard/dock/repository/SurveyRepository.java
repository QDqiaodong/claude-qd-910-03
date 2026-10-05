package com.shipyard.dock.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.dto.SurveyVerdictStatDTO;

public interface SurveyRepository extends JpaRepository<Survey, Long> {

    List<Survey> findAllByOrderBySurveyDateDesc();

    boolean existsBySurveyNo(String surveyNo);

    List<Survey> findByBlockIdOrderBySurveyDateDesc(Long blockId);

    /** 分段当前有效的报验结论（最新一张报验单） */
    Optional<Survey> findFirstByBlockIdOrderBySurveyDateDescIdDesc(Long blockId);

    List<Survey> findByVesselIdOrderBySurveyDateDesc(Long vesselId);

    @Query("select s from Survey s where s.vesselId = :vesselId and s.result = 'PASS' order by s.surveyDate desc")
    List<Survey> findPassedOfVessel(@Param("vesselId") Long vesselId);

    /**
     * 手写 JPQL ④：按报验结论统计单数。
     */
    @Query("select new com.shipyard.dock.dto.SurveyVerdictStatDTO(s.result, count(s)) "
            + "from Survey s group by s.result order by count(s) desc")
    List<SurveyVerdictStatDTO> statByResult();
}
