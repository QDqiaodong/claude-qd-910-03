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
 * 船舶：本厂在造 / 在修的船。船号是本厂编号，船检登记号是法定登记号，两者都唯一。
 */
@Entity
@Table(name = "vessel")
@Getter
@Setter
public class Vessel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 本厂船号，唯一（分段编码要用它拼码） */
    @Column(name = "vessel_no", nullable = false, unique = true, length = 24)
    private String vesselNo;

    /** 船检登记号，唯一 */
    @Column(name = "reg_no", nullable = false, unique = true, length = 24)
    private String regNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    /** 船舶类型：BULK / TANKER / CONTAINER / PASSENGER / ENGINEERING */
    @Column(name = "vessel_type", length = 16)
    private String vesselType;

    /** 归属船东，必须存在 */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_name", length = 48)
    private String ownerName;

    /** 船长（米），必须 > 0 */
    @Column(name = "length_m", precision = 10, scale = 2)
    private BigDecimal lengthM;

    /** 型宽（米） */
    @Column(name = "width_m", precision = 10, scale = 2)
    private BigDecimal widthM;

    /** 型深（米） */
    @Column(name = "depth_m", precision = 10, scale = 2)
    private BigDecimal depthM;

    /** 总吨 */
    @Column(name = "gross_tonnage", precision = 12, scale = 2)
    private BigDecimal grossTonnage;

    /** 排水量（吨），排船台时要和船台承载能力比 */
    @Column(name = "displacement_t", precision = 12, scale = 2)
    private BigDecimal displacementT;

    @Column(name = "build_year")
    private Integer buildYear;

    /** 状态：IN_YARD / LAUNCHED / DELIVERED */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getVesselTypeName() {
        return Dict.vesselTypeName(vesselType);
    }

    @Transient
    public String getStatusName() {
        return Dict.vesselStatusName(status);
    }
}
