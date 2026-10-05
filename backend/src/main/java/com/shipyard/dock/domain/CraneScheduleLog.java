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
 * 起重设备排程操作日志：检修停用、自动改派、待复核保留、吊次开始、安全处置、恢复确认等
 * 每一次影响设备归属的动作都在此留痕。只追加、不修改、不删除，作为责任追溯依据。
 */
@Entity
@Table(name = "crane_schedule_log")
@Getter
@Setter
public class CraneScheduleLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 动作类型，见 Dict.SCHEDULE_ACTION_* */
    @Column(name = "action_type", length = 24, nullable = false)
    private String actionType;

    @Column(name = "crane_id")
    private Long craneId;

    @Column(name = "crane_no", length = 16)
    private String craneNo;

    @Column(name = "crane_name", length = 48)
    private String craneName;

    /** 关联的替代 / 原设备（如改派动作的目标设备） */
    @Column(name = "other_crane_id")
    private Long otherCraneId;

    @Column(name = "other_crane_no", length = 16)
    private String otherCraneNo;

    @Column(name = "erection_id")
    private Long erectionId;

    @Column(name = "erection_no", length = 24)
    private String erectionNo;

    @Column(name = "lift_id")
    private Long liftId;

    @Column(name = "lift_no", length = 24)
    private String liftNo;

    @Column(name = "block_code", length = 24)
    private String blockCode;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getActionTypeName() {
        return Dict.scheduleActionName(actionType);
    }
}
