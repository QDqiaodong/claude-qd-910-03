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
 * 船舶移泊记录：每一次移泊尝试（含待排区 ↔ 船台）都在此留痕——
 * 原船台、目标船台、操作时间、结果与失败原因。只追加、不修改、不删除，作为责任追溯依据。
 */
@Entity
@Table(name = "berth_move_log")
@Getter
@Setter
public class BerthMoveLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    /** 原船台（空表示从待排区排入） */
    @Column(name = "from_berth_id")
    private Long fromBerthId;

    @Column(name = "from_berth_no", length = 16)
    private String fromBerthNo;

    @Column(name = "to_berth_id")
    private Long toBerthId;

    @Column(name = "to_berth_no", length = 16)
    private String toBerthNo;

    /** 结果：SUCCESS 成功 / FAIL 失败 */
    @Column(name = "result", length = 16, nullable = false)
    private String result;

    /** 结果说明：成功时的移泊摘要，或失败原因（含当前真实船位） */
    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getResultName() {
        return Dict.moveResultName(result);
    }
}
