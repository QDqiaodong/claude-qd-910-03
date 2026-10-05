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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 下水放行单：面向船台调度与质量人员的放行凭证。
 *
 * <p>把在台船舶、所属合同、全部分段的搭载与交验进度、未闭环遗留项汇成一张可追溯的单据。
 * 生成时的关键快照（{@code snapshotJson} / {@code blockersJson}）落库后不再改写，
 * 后续整改的每一次复核都追加到 {@code launch_clearance_check}，旧证据不被新数据覆盖。</p>
 *
 * <p>状态流转：待确认 → 已放行 / 已作废；已放行不可作废，已作废可另开新单（新单回引被作废单号）。</p>
 */
@Entity
@Table(name = "launch_clearance")
@Getter
@Setter
public class LaunchClearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 放行单号，唯一，格式 LC-yyyyMMdd-序号 */
    @Column(name = "clearance_no", nullable = false, unique = true, length = 24)
    private String clearanceNo;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    /** 生成时船舶占用的船台 */
    @Column(name = "berth_id")
    private Long berthId;

    @Column(name = "berth_no", length = 16)
    private String berthNo;

    @Column(name = "berth_name", length = 48)
    private String berthName;

    /** 生成时认定的所属合同（当前履行中的主合同） */
    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "contract_no", length = 24)
    private String contractNo;

    @Column(name = "contract_status", length = 16)
    private String contractStatus;

    /** 状态：PENDING 待确认 / CONFIRMED 已放行 / VOID 已作废 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 生成时校验结论：READY 可放行 / BLOCKED 有阻塞 */
    @Column(name = "verdict", nullable = false, length = 16)
    private String verdict;

    /** 最近一次复核结论（复核只追加记录、不改生成快照，这里冗余一份便于列表展示） */
    @Column(name = "latest_verdict", length = 16)
    private String latestVerdict;

    @Column(name = "latest_checked_at")
    private LocalDateTime latestCheckedAt;

    /** 生成时统计：分段总数 */
    @Column(name = "block_count")
    private Integer blockCount;

    /** 生成时统计：已完成搭载的分段数 */
    @Column(name = "erected_count")
    private Integer erectedCount;

    /** 生成时统计：取得有效合格 / 让步结论的分段数 */
    @Column(name = "survey_valid_count")
    private Integer surveyValidCount;

    /** 生成时统计：未闭环的严重遗留项数 */
    @Column(name = "critical_open_count")
    private Integer criticalOpenCount;

    /** 生成时统计：全部未闭环遗留项数 */
    @Column(name = "punch_open_count")
    private Integer punchOpenCount;

    /** 生成时关键快照（船舶 / 船台 / 合同 / 分段 / 遗留项 / 阻塞明细），不可变 */
    @Column(name = "snapshot_json")
    private String snapshotJson;

    /** 生成时阻塞明细，不可变 */
    @Column(name = "blockers_json")
    private String blockersJson;

    /** 作废重开时，新单回引被作废的单号 */
    @Column(name = "previous_no", length = 24)
    private String previousNo;

    /** 质量人员签认（确认放行的前置条件；作废重开后新单需重新签认） */
    @Column(name = "signed_by", length = 32)
    private String signedBy;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "generated_by", length = 32)
    private String generatedBy;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "voided_by", length = 32)
    private String voidedBy;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "void_reason", length = 128)
    private String voidReason;

    /** 乐观锁：配合确认时的行级悲观锁，双保险防重复确认 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getStatusName() {
        return Dict.clearanceStatusName(status);
    }

    @Transient
    public String getVerdictName() {
        return Dict.clearanceVerdictName(verdict);
    }

    @Transient
    public String getLatestVerdictName() {
        return latestVerdict == null ? "" : Dict.clearanceVerdictName(latestVerdict);
    }

    @Transient
    public String getContractStatusName() {
        return Dict.contractStatusName(contractStatus);
    }

    @Transient
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    @Transient
    public boolean isConfirmed() {
        return "CONFIRMED".equals(status);
    }

    @Transient
    public boolean isSigned() {
        return signedBy != null && !signedBy.isBlank();
    }
}
