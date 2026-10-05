package com.shipyard.dock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import lombok.Getter;
import lombok.Setter;

/**
 * 修造合同：草拟 → 已签 → 履行中（进入结算流程，按付款节点逐笔结算）→ 已结算（全部节点已付款）。
 *
 * <p>结算口径：每个付款节点的应付金额 = 合同金额 × 节点比例，在确认付款时按当时合同金额
 * 冻结到节点上；能否确认由后端统一依据「合同金额 + 已完成的报验结论（合格 / 让步接收）
 * + 当前船舶状态 + 前序节点」实时计算，条件不满足时节点停留在待结算并逐条记录阻塞项。</p>
 *
 * <p>{@code paymentState} 等结算字段是服务端实时计算的瞬时字段（不落库），合同详情、
 * 船舶合同列表、报验详情三处共用同一份计算结果，刷新后状态与阻塞原因必然一致。</p>
 */
@Entity
@Table(name = "contract")
@Getter
@Setter
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 合同编号，唯一 */
    @Column(name = "contract_no", nullable = false, unique = true, length = 24)
    private String contractNo;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_name", length = 48)
    private String ownerName;

    /** 合同类型：NEW_BUILD / REPAIR / CONVERSION */
    @Column(name = "contract_type", length = 16)
    private String contractType;

    /** 合同金额（万元），必须 > 0；付款节点的应付金额全部由它按比例算出 */
    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    /** 已付款金额（万元）：由已付款节点冻结金额求和得出，只随确认付款这一个动作增长 */
    @Column(name = "paid_amount", precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "sign_date")
    private LocalDate signDate;

    /** 交船期 */
    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    /** 状态：DRAFT / SIGNED / PERFORMING / SETTLED */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** 结算时引用的最终报验通过单号（最后一笔付款确认时记录） */
    @Column(name = "settle_ref", length = 32)
    private String settleRef;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ---------------- 以下为结算联动的实时计算字段（@Transient，不落库） ----------------

    /** 结算状态：NOT_STARTED 未进入结算 / PENDING 待结算 / PARTIAL 部分已付 / PAID 全部已付 */
    @Transient
    private String paymentState;

    @Transient
    private String paymentStateName;

    /** 结算流程是否已开始（履行中 / 已结算，已生成付款节点） */
    @Transient
    private Boolean settlementStarted;

    /** 已付款节点数 / 节点总数 */
    @Transient
    private Integer paidNodeCount;

    @Transient
    private Integer totalNodeCount;

    /** 已付款金额（与持久化 paidAmount 同源，便于前端直接取展示值） */
    @Transient
    private BigDecimal settledAmount;

    /** 最早一个待结算节点的阻塞项（列表卡片用；完整阻塞见各节点 blockers） */
    @Transient
    private List<Map<String, Object>> blockers;

    /** 已结算合同的依据回退提示：已付款不会被回退，但提示当时依据与现状的差异 */
    @Transient
    private List<String> settlementWarnings;

    /** 付款节点（详情接口按顺序返回，列表接口可不填充） */
    @Transient
    private List<Map<String, Object>> paymentNodes;

    @Transient
    public String getContractTypeName() {
        return Dict.contractTypeName(contractType);
    }

    @Transient
    public String getStatusName() {
        return Dict.contractStatusName(status);
    }

    @Transient
    public boolean isSettled() {
        return "SETTLED".equals(status);
    }
}
