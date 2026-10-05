package com.shipyard.dock.domain;

import java.time.LocalDate;
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
 * 搭载任务：把某一段按搭载顺序号吊到某船台上。顺序必须按分段编号顺序推进，跳序拦截。
 */
@Entity
@Table(name = "erection")
@Getter
@Setter
public class Erection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 搭载任务号，唯一，service 端生成 ERE-船号-序号 */
    @Column(name = "erection_no", nullable = false, unique = true, length = 24)
    private String erectionNo;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "block_code", length = 24)
    private String blockCode;

    /** 搭载顺序号，和分段的 seg_seq 一致 */
    @Column(name = "seg_seq")
    private Integer segSeq;

    @Column(name = "berth_id")
    private Long berthId;

    @Column(name = "berth_no", length = 16)
    private String berthNo;

    @Column(name = "plan_date")
    private LocalDate planDate;

    /** 状态：PLANNED / READY / DONE */
    @Column(name = "status", length = 16)
    private String status;

    // ---------------- 排程设备归属（检修联动核心字段） ----------------

    /** 当前排程设备：未开始任务可随检修改派；已开始 / 已完成任务此归属冻结 */
    @Column(name = "crane_id")
    private Long craneId;

    @Column(name = "crane_no", length = 16)
    private String craneNo;

    @Column(name = "crane_name", length = 48)
    private String craneName;

    /** 检修前的原排程设备，待复核期间保留；恢复时可据此还原 */
    @Column(name = "original_crane_id")
    private Long originalCraneId;

    @Column(name = "original_crane_no", length = 16)
    private String originalCraneNo;

    @Column(name = "original_crane_name", length = 48)
    private String originalCraneName;

    /** 待复核：原设备检修停用且尚未决定恢复 / 保留替代期间为 true */
    @Column(name = "review_pending")
    private Boolean reviewPending;

    /** 最近一次排程处置说明（自动改派 / 待复核 / 恢复 / 保留替代） */
    @Column(name = "schedule_note", length = 255)
    private String scheduleNote;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    /** 乐观锁：检修、改派、吊次开始、恢复几乎同时发生时，保证同一任务只有一个有效设备归属 */
    @Version
    @Column(name = "version")
    private Integer version;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getStatusName() {
        return Dict.erectionStatusName(status);
    }

    @Transient
    public boolean isDone() {
        return "DONE".equals(status);
    }

    /** 任务是否尚未开始吊装（只有未开始任务允许改派 / 待复核 / 恢复） */
    @Transient
    public boolean isNotStarted() {
        return "PLANNED".equals(status) || "READY".equals(status);
    }

    @Transient
    public boolean isReviewPending() {
        return Boolean.TRUE.equals(reviewPending);
    }
}
