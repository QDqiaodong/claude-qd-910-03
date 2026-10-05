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
 * 起重设备：龙门吊 / 塔吊 / 履带吊 / 浮吊，吊次登记时的额定起重量上限就来自这里。
 */
@Entity
@Table(name = "crane")
@Getter
@Setter
public class Crane {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备编号，唯一 */
    @Column(name = "crane_no", nullable = false, unique = true, length = 16)
    private String craneNo;

    @Column(name = "crane_name", length = 48)
    private String craneName;

    /** 类型：GANTRY / TOWER / CRAWLER / FLOATING */
    @Column(name = "crane_type", length = 16)
    private String craneType;

    /** 额定起重量（吨），吊次重量不得超它 */
    @Column(name = "rated_load_t", precision = 10, scale = 2)
    private BigDecimal ratedLoadT;

    @Column(name = "span_m", precision = 10, scale = 2)
    private BigDecimal spanM;

    @Column(name = "lift_height_m", precision = 10, scale = 2)
    private BigDecimal liftHeightM;

    /** 状态：AVAILABLE / MAINTAINING / RETIRED */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "location", length = 32)
    private String location;

    @Column(name = "note", length = 64)
    private String note;

    /** 进入检修 / 停用的原因说明 */
    @Column(name = "unavailable_reason", length = 128)
    private String unavailableReason;

    /** 进入当前非可用状态的时间 */
    @Column(name = "maintenance_from")
    private LocalDateTime maintenanceFrom;

    /** 置为检修 / 停用的操作人（责任追溯） */
    @Column(name = "maintenance_operator", length = 32)
    private String maintenanceOperator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getCraneTypeName() {
        return Dict.craneTypeName(craneType);
    }

    @Transient
    public String getStatusName() {
        return Dict.craneStatusName(status);
    }

    @Transient
    public boolean isAvailable() {
        return "AVAILABLE".equals(status);
    }
}
