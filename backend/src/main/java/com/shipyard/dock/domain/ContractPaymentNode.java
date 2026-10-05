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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 合同付款节点：合同进入履行中（进入结算流程）时按模板生成的分期付款计划。
 *
 * <p>节点是否可结算由三部分共同决定：合同金额（应付金额 = 金额 × 比例）、
 * 已完成的报验结论（交验进度款 / 质保金）、当前船舶状态（下水款 / 交船款 / 质保金）。
 * 条件未满足的节点停留在「待结算」并在 {@code blockersJson} 逐条列明阻塞项。</p>
 *
 * <p>状态流转：待结算 → 可结算 → 已结算。已结算为终态：
 * 报验补录 / 撤回 / 改判让步触发的联动重算只刷新未结算节点，已完成节点不被覆盖。</p>
 *
 * <p>并发：{@code (contract_id, node_code)} 唯一约束 + {@code @Version} 乐观锁 +
 * 确认时行级悲观锁，多人同时确认或网络重试只允许一个有效结果。</p>
 */
@Entity
@Table(name = "contract_payment_node")
@Getter
@Setter
public class ContractPaymentNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "contract_no", length = 24)
    private String contractNo;

    @Column(name = "vessel_id")
    private Long vesselId;

    /** 节点代码：SIGN 签订款 / SURVEY 交验款 / LAUNCH 下水款 / DELIVERY 交船款 / WARRANTY 质保金 */
    @Column(name = "node_code", nullable = false, length = 16)
    private String nodeCode;

    @Column(name = "node_name", length = 32)
    private String nodeName;

    /** 节点顺序（签订 → 交验 → 下水 → 交船 → 质保） */
    @Column(name = "seq")
    private Integer seq;

    /** 应付比例（占合同金额） */
    @Column(name = "ratio", precision = 6, scale = 4)
    private BigDecimal ratio;

    /** 当前应付金额（按当前合同金额计算；已结算后为历史值，不再随金额变更刷新） */
    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    /** 状态：PENDING 待结算 / DUE 可结算 / SETTLED 已结算 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 最近一次重算的阻塞明细（可结算 / 已结算时为空数组） */
    @Column(name = "blockers_json")
    private String blockersJson;

    /** 实际结算金额（确认时快照，等于当时的应付金额） */
    @Column(name = "settled_amount", precision = 14, scale = 2)
    private BigDecimal settledAmount;

    @Column(name = "settled_by", length = 32)
    private String settledBy;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** 乐观锁：配合确认时的行级悲观锁，双保险防重复确认 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    public String getStatusName() {
        return Dict.paymentNodeStatusName(status);
    }

    @Transient
    public boolean isSettled() {
        return "SETTLED".equals(status);
    }
}
