package com.shipyard.dock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.shipyard.dock.common.Dict;
import com.shipyard.dock.generation.SegmentCode;

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
 * 船体分段：船厂真正干活的最小单元。
 *
 * <p>分段编码 {@code blockCode} 由 {@link SegmentCode} 注解 +
 * Hibernate {@code @ValueGenerationType} 在插入前自动生成，格式 {@code SEG-船号-序号}。</p>
 *
 * <p>状态流转：预制 → 涂装 → 搭载 → 合拢 → 交验，逐级推进，跳阶段拦截；合拢前必须已涂装完成。</p>
 */
@Entity
@Table(name = "block")
@Getter
@Setter
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 分段编码，插入时由生成器生成，格式 SEG-船号-序号 */
    @SegmentCode(prefix = "SEG", width = 3)
    @Column(name = "block_code", nullable = false, unique = true, length = 24)
    private String blockCode;

    /** 所属船舶，必须是本厂的船 */
    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    @Column(name = "block_name", length = 48)
    private String blockName;

    /** 分段类型：BOTTOM / SIDE / DECK / BOW / STERN / SUPER */
    @Column(name = "block_type", length = 16)
    private String blockType;

    /** 分段重量（吨），必须 > 0 */
    @Column(name = "weight_t", precision = 10, scale = 2)
    private BigDecimal weightT;

    /** 搭载顺序号：同一条船内按此号从小到大推进 */
    @Column(name = "seg_seq")
    private Integer segSeq;

    /** 状态：PREFAB / PAINT / ERECTION / CLOSURE / SURVEY */
    @Column(name = "status", length = 16)
    private String status;

    /** 是否已完成涂装（合拢前置条件） */
    @Column(name = "painted")
    private Boolean painted;

    @Column(name = "paint_finish_date")
    private LocalDate paintFinishDate;

    /** 建造场地 */
    @Column(name = "workshop", length = 32)
    private String workshop;

    @Column(name = "plan_start_date")
    private LocalDate planStartDate;

    @Column(name = "actual_finish_date")
    private LocalDate actualFinishDate;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getBlockTypeName() {
        return Dict.blockTypeName(blockType);
    }

    @Transient
    public String getStatusName() {
        return Dict.blockStatusName(status);
    }

    @Transient
    public int getStatusIndex() {
        int index = Dict.blockStatusIndex(status);
        return index < 0 ? 0 : index;
    }

    /** 阶段进度 %：预制 20 / 涂装 40 / 搭载 60 / 合拢 80 / 交验 100 */
    @Transient
    public int getProgress() {
        return (getStatusIndex() + 1) * 20;
    }

    @Transient
    public boolean isPainted() {
        return Boolean.TRUE.equals(painted);
    }
}
