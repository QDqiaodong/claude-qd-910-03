package com.shipyard.dock.domain;

import java.time.LocalDateTime;

import com.shipyard.dock.common.Dict;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 付款节点操作记录（只追加，责任追溯）。
 *
 * <p>进入结算生成节点、报验补录 / 撤回 / 改为让步接收或船舶状态变化触发的联动重算、
 * 确认付款、付款确认被驳回，都会追加一条记录，并带上当时的合同金额、有效报验结论、
 * 船舶状态与阻塞明细快照。旧记录永远不被新数据覆盖，事后能解释每一笔款当时是
 * 依据什么金额、什么报验结论、什么船舶状态算出来 / 为什么被拦下。</p>
 */
@Entity
@Table(name = "contract_payment_log")
@Getter
@Setter
public class ContractPaymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "node_no", length = 40)
    private String nodeNo;

    @Column(name = "vessel_id")
    private Long vesselId;

    /** 动作：CREATE / RECALC / CONFIRM / CONFIRM_REJECT */
    @Column(name = "action", nullable = false, length = 24)
    private String action;

    /** 联动来源：SURVEY_UPSERT / SURVEY_DELETE / VESSEL_STATUS / CONTRACT_AMOUNT / RECHECK */
    @Column(name = "source_type", length = 24)
    private String sourceType;

    /** 触发本次记录的关联对象（如报验单号 / 船舶号 / 合同号） */
    @Column(name = "source_ref", length = 32)
    private String sourceRef;

    /** 当时判定结论：READY 可付款 / BLOCKED 有阻塞 */
    @Column(name = "verdict", length = 16)
    private String verdict;

    /** 当时合同金额（万元） */
    @Column(name = "contract_amount", precision = 14, scale = 2)
    private java.math.BigDecimal contractAmount;

    /** 当时有效报验结论条数 */
    @Column(name = "accept_survey_count")
    private Integer acceptSurveyCount;

    /** 当时船舶状态 */
    @Column(name = "vessel_status", length = 16)
    private String vesselStatus;

    /** 当时阻塞明细快照 JSON（可付款时为空数组） */
    @Column(name = "blockers_json", columnDefinition = "TEXT")
    private String blockersJson;

    /** 当时完整证据快照 JSON（金额 / 报验结论 / 船舶状态 / 前序节点 / 应付金额） */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getActionName() {
        return Dict.paymentActionName(action);
    }

    @Transient
    public String getSourceTypeName() {
        return Dict.paymentSourceName(sourceType);
    }

    @Transient
    public String getVerdictName() {
        if (verdict == null) {
            return "";
        }
        return "READY".equals(verdict) ? "可付款" : "有阻塞";
    }

    @Transient
    public String getVesselStatusName() {
        return Dict.vesselStatusName(vesselStatus);
    }
}
