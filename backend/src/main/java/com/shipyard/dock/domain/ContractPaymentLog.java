package com.shipyard.dock.domain;

import java.math.BigDecimal;
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
 * 合同付款节点记录：付款节点生命周期里每一次生成、联动重算与确认结算的证据链（只追加）。
 *
 * <p>每条记录留存当时使用的合同金额、节点金额、报验结论快照与船舶状态，
 * 让历史能解释每一次状态变化的依据；确认记录与操作者痕迹永不改写，
 * 报验补录 / 撤回 / 改判让步触发的重算只追加新记录，不覆盖旧记录。</p>
 */
@Entity
@Table(name = "contract_payment_log")
@Getter
@Setter
public class ContractPaymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "contract_no", length = 24)
    private String contractNo;

    @Column(name = "node_code", length = 16)
    private String nodeCode;

    @Column(name = "node_name", length = 32)
    private String nodeName;

    /** 记录类型：GENERATE 生成节点 / RECALC 联动重算 / CONFIRM 确认结算 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** 当时合同金额（万元） */
    @Column(name = "contract_amount", precision = 14, scale = 2)
    private BigDecimal contractAmount;

    /** 当时节点应付 / 结算金额（万元） */
    @Column(name = "node_amount", precision = 14, scale = 2)
    private BigDecimal nodeAmount;

    /** 当时报验结论快照（各分段最新结论与合格 / 让步 / 不合格计数） */
    @Column(name = "survey_summary_json")
    private String surveySummaryJson;

    /** 当时船舶状态：IN_YARD / LAUNCHED / DELIVERED */
    @Column(name = "vessel_status", length = 16)
    private String vesselStatus;

    /** 当时阻塞明细快照（无阻塞时为空数组） */
    @Column(name = "blockers_json")
    private String blockersJson;

    /** 当时重算 / 操作后的节点状态：PENDING / DUE / SETTLED */
    @Column(name = "result", length = 16)
    private String result;

    /** 操作者（确认结算为确认人；联动重算为触发源，如「报验补录 SV-xxx」） */
    @Column(name = "operator", length = 48)
    private String operator;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getActionName() {
        return Dict.paymentLogActionName(action);
    }

    @Transient
    public String getVesselStatusName() {
        return Dict.vesselStatusName(vesselStatus);
    }

    @Transient
    public String getResultName() {
        return Dict.paymentNodeStatusName(result);
    }
}
