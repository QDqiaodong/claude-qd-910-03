package com.shipyard.dock.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.shipyard.dock.common.Dict;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 合同付款节点：合同进入结算流程（履行中）后按合同类型模板逐笔生成，例如
 * 新船建造「开工款 → 上船台款 → 下水款 → 交船款」。
 *
 * <p>待结算（{@code PENDING}）期间，应付比例固定、应付金额随合同金额实时计算，
 * 能否付款由后端依据合同金额、有效报验结论、当前船舶状态与前序节点统一判定，
 * 阻塞项逐条落到 {@code blockers_json}，而不是只在页面上禁用按钮。</p>
 *
 * <p>确认付款（{@code PAID}）时把当时的合同金额、应付金额、报验结论、船舶状态冻结为快照，
 * 之后任何报验补录 / 撤回 / 让步改判、合同金额调整、船舶状态变化都不得倒改已付款节点
 * （历史不可被刷新悄悄覆盖）；{@code version} 乐观锁配合确认时的行级悲观锁，
 * 保证多人同时确认 / 网络重试时同一节点只有一笔有效付款。</p>
 */
@Entity
@Table(name = "contract_payment_node")
@Getter
@Setter
public class ContractPaymentNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 节点号，PM-合同号-序号（如 PM-CT-2026-011-01） */
    @Column(name = "node_no", nullable = false, unique = true, length = 40)
    private String nodeNo;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "contract_no", length = 24)
    private String contractNo;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    /** 节点顺序（从 1 开始，必须逐笔确认） */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    /** 节点名称：开工款 / 上船台款 / 下水款 / 交船款 等 */
    @Column(name = "node_name", length = 32)
    private String nodeName;

    /** 应付比例（百分比，节点合计 100） */
    @Column(name = "ratio", nullable = false)
    private Integer ratio;

    /** 状态：PENDING 待结算 / PAID 已付款 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 条件模板：要求船舶至少到达的状态 IN_YARD / LAUNCHED / DELIVERED */
    @Column(name = "required_vessel_status", length = 16)
    private String requiredVesselStatus;

    /** 条件模板：要求该船已完成的合格 / 让步接收报验结论条数 */
    @Column(name = "required_accept_surveys")
    private Integer requiredAcceptSurveys;

    /** 待结算时实时计算的应付金额（万元）= 当前合同金额 × 比例；已付款时取冻结值 */
    @Column(name = "amount_due", precision = 14, scale = 2)
    private BigDecimal amountDue;

    /** 付款时冻结的实际付款金额（万元），此后不再随合同金额变化 */
    @Column(name = "amount_paid", precision = 14, scale = 2)
    private BigDecimal amountPaid;

    /** 付款时冻结的合同金额（万元），解释这笔钱是按哪个金额算出来的 */
    @Column(name = "frozen_contract_amount", precision = 14, scale = 2)
    private BigDecimal frozenContractAmount;

    /** 最近一次重算结论：READY 可付款 / BLOCKED 有阻塞（仅待结算节点随联动刷新） */
    @Column(name = "latest_verdict", length = 16)
    private String latestVerdict;

    @Column(name = "latest_checked_at")
    private LocalDateTime latestCheckedAt;

    /** 最近一次重算的阻塞明细 JSON（可付款时为空数组），三处页面展示同一份原因 */
    @Column(name = "blockers_json", columnDefinition = "TEXT")
    private String blockersJson;

    /** 付款确认时冻结的证据快照：合同金额、报验结论、船舶状态、前序节点，落库后不再改写 */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    /** 付款时引用的报验单号（满足条件的有效报验结论中最新一张） */
    @Column(name = "settle_ref", length = 32)
    private String settleRef;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 乐观锁：与确认时的行级悲观锁双保险，重复确认整体回滚，不留扣了钱没更新的半成品 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    // ---------------- 实时计算的瞬时字段（不落库） ----------------

    @Transient
    private String statusName;

    @Transient
    private String latestVerdictName;

    @Transient
    private String requiredVesselStatusName;

    /** 解析后的阻塞项列表 */
    @Transient
    private List<Map<String, Object>> blockers;

    /** 解析后的付款证据快照 */
    @Transient
    private Map<String, Object> evidence;

    /** 判定时该船当前有效的报验结论条数（合格 + 让步接收，按每张报验单最新结论计） */
    @Transient
    private Integer currentAcceptCount;

    /** 判定时最新一张有效报验单号 */
    @Transient
    private String currentSurveyNo;

    /** 判定时最新一张有效报验结论 PASS / CONCESSION */
    @Transient
    private String currentSurveyResult;

    /** 判定时船舶状态 IN_YARD / LAUNCHED / DELIVERED */
    @Transient
    private String currentVesselStatus;

    @Transient
    public String getStatusName() {
        return Dict.paymentNodeStatusName(status);
    }

    @Transient
    public String getLatestVerdictName() {
        if (latestVerdict == null) {
            return "";
        }
        return "READY".equals(latestVerdict) ? "可付款" : "有阻塞";
    }

    @Transient
    public String getRequiredVesselStatusName() {
        return Dict.vesselStatusName(requiredVesselStatus);
    }

    @Transient
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    @Transient
    public boolean isPaid() {
        return "PAID".equals(status);
    }
}
