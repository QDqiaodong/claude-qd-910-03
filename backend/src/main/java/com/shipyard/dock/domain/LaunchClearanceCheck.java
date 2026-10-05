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
 * 放行单复核 / 操作记录：放行单生命周期里每一次校验与操作的证据链。
 *
 * <p>生成、整改复核、质量签认、确认放行、作废都会追加一条记录，
 * 记录当时的校验结论与阻塞明细快照，让后续能解释当时为什么允许或拒绝下水。</p>
 */
@Entity
@Table(name = "launch_clearance_check")
@Getter
@Setter
public class LaunchClearanceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clearance_id", nullable = false)
    private Long clearanceId;

    /** 记录类型：GENERATE 生成 / RECHECK 复核 / SIGN 签认 / CONFIRM 确认放行 / VOID 作废 */
    @Column(name = "check_type", nullable = false, length = 16)
    private String checkType;

    /** 当时校验结论：READY 可放行 / BLOCKED 有阻塞 */
    @Column(name = "verdict", length = 16)
    private String verdict;

    /** 当时阻塞明细快照（可放行时为空数组） */
    @Column(name = "blockers_json")
    private String blockersJson;

    /** 当时汇总数字快照（分段 / 交验 / 遗留项计数） */
    @Column(name = "summary_json")
    private String summaryJson;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getCheckTypeName() {
        return Dict.clearanceCheckTypeName(checkType);
    }

    @Transient
    public String getVerdictName() {
        return Dict.clearanceVerdictName(verdict);
    }
}
