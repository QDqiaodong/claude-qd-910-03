package com.shipyard.dock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 修造合同：草拟 → 已签 → 履行中 → 已结算；结算前必须引用至少一次报验通过记录。
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

    /** 合同金额（万元），必须 > 0 */
    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    /** 已结算金额（万元）：付款节点确认时在同一事务内累加，与节点状态同生共死 */
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

    /** 结算时引用的报验通过单号 */
    @Column(name = "settle_ref", length = 32)
    private String settleRef;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 付款节点结算摘要（节点进度 / 已结算金额 / 当前阻塞项），由服务层填充。
     * 合同详情、船舶合同列表与报验详情都带同一份摘要，保证三处口径一致。
     */
    @Transient
    private Map<String, Object> paymentSummary;

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
