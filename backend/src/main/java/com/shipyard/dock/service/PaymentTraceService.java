package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.dock.domain.ContractPaymentLog;
import com.shipyard.dock.repository.ContractPaymentLogRepository;

/**
 * 付款节点操作记录落库器。
 *
 * <p>单独拆一个 REQUIRES_NEW 事务，是为了「付款确认被驳回」时：主事务整体回滚
 * （不扣已付金额、不更新节点，杜绝半完成状态），但这次失败尝试的操作者痕迹
 * 与当时阻塞项仍能独立落库，解释谁在什么时候因为什么被拦下。</p>
 */
@Service
public class PaymentTraceService {

    private final ContractPaymentLogRepository logRepository;
    private final ObjectMapper objectMapper;

    public PaymentTraceService(ContractPaymentLogRepository logRepository, ObjectMapper objectMapper) {
        this.logRepository = logRepository;
        this.objectMapper = objectMapper;
    }

    /** 独立事务落一条记录：驳回记录必须在主事务回滚后仍然保留 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReject(Long contractId, Long nodeId, String nodeNo, Long vesselId,
            String sourceRef, BigDecimal contractAmount, Integer acceptSurveyCount, String vesselStatus,
            List<Map<String, Object>> blockers, Map<String, Object> evidence,
            String operator, String note) {
        save("CONFIRM_REJECT", contractId, nodeId, nodeNo, vesselId, "RECHECK", sourceRef,
                "BLOCKED", contractAmount, acceptSurveyCount, vesselStatus, blockers, evidence, operator, note);
    }

    /** 普通记录：跟随调用方事务，与业务更新同生共死 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, Long contractId, Long nodeId, String nodeNo, Long vesselId,
            String sourceType, String sourceRef, String verdict, BigDecimal contractAmount,
            Integer acceptSurveyCount, String vesselStatus, List<Map<String, Object>> blockers,
            Map<String, Object> evidence, String operator, String note) {
        save(action, contractId, nodeId, nodeNo, vesselId, sourceType, sourceRef, verdict,
                contractAmount, acceptSurveyCount, vesselStatus, blockers, evidence, operator, note);
    }

    private void save(String action, Long contractId, Long nodeId, String nodeNo, Long vesselId,
            String sourceType, String sourceRef, String verdict, BigDecimal contractAmount,
            Integer acceptSurveyCount, String vesselStatus, List<Map<String, Object>> blockers,
            Map<String, Object> evidence, String operator, String note) {
        ContractPaymentLog log = new ContractPaymentLog();
        log.setContractId(contractId);
        log.setNodeId(nodeId);
        log.setNodeNo(nodeNo);
        log.setVesselId(vesselId);
        log.setAction(action);
        log.setSourceType(sourceType);
        log.setSourceRef(sourceRef);
        log.setVerdict(verdict);
        log.setContractAmount(contractAmount);
        log.setAcceptSurveyCount(acceptSurveyCount);
        log.setVesselStatus(vesselStatus);
        log.setBlockersJson(toJson(blockers));
        log.setEvidenceJson(toJson(evidence));
        log.setOperator(operator);
        log.setNote(note);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.save(log);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("付款节点操作记录序列化失败", e);
        }
    }
}
