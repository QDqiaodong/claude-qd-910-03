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
import lombok.Getter;
import lombok.Setter;

/**
 * 船台 / 船坞 / 舾装码头：船厂最紧俏的资源，一格一个泊位。
 *
 * <p>状态流转：空闲 → 占用 → 检修 → 空闲；占用中的船台不允许再排入新船。</p>
 */
@Entity
@Table(name = "berth")
@Getter
@Setter
public class Berth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 船台编号，唯一 */
    @Column(name = "berth_no", nullable = false, unique = true, length = 16)
    private String berthNo;

    @Column(name = "berth_name", length = 48)
    private String berthName;

    /** 类型：SLIPWAY 船台 / DRYDOCK 船坞 / WHARF 舾装码头 */
    @Column(name = "berth_type", length = 16)
    private String berthType;

    /** 有效长度（米），必须 > 0 */
    @Column(name = "length_m", precision = 10, scale = 2)
    private BigDecimal lengthM;

    /** 有效宽度（米），必须 > 0 */
    @Column(name = "width_m", precision = 10, scale = 2)
    private BigDecimal widthM;

    /** 承载能力（吨），必须 > 0 */
    @Column(name = "capacity_t", precision = 12, scale = 2)
    private BigDecimal capacityT;

    /** 当前占用船舶 */
    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    /** 状态：IDLE / OCCUPIED / MAINTENANCE */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "occupied_at")
    private LocalDateTime occupiedAt;

    @Column(name = "location", length = 32)
    private String location;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getBerthTypeName() {
        return Dict.berthTypeName(berthType);
    }

    @Transient
    public String getStatusName() {
        return Dict.berthStatusName(status);
    }

    @Transient
    public boolean isIdle() {
        return "IDLE".equals(status);
    }

    @Transient
    public boolean isOccupied() {
        return "OCCUPIED".equals(status);
    }

    /** 占用率提示：船台上已有船 */
    @Transient
    public boolean isBusy() {
        return !"IDLE".equals(status);
    }
}
