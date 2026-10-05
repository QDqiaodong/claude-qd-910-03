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
 * 船东（客户）：船舶必须归属于名录内的船东，合同也挂在船东名下。
 */
@Entity
@Table(name = "owner")
@Getter
@Setter
public class Owner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 船东编号，唯一 */
    @Column(name = "owner_no", nullable = false, unique = true, length = 16)
    private String ownerNo;

    @Column(name = "owner_name", nullable = false, length = 48)
    private String ownerName;

    /** 类型：STATE / PRIVATE / FOREIGN */
    @Column(name = "owner_type", length = 16)
    private String ownerType;

    @Column(name = "contact_person", length = 32)
    private String contactPerson;

    @Column(name = "contact_phone", length = 24)
    private String contactPhone;

    @Column(name = "country", length = 24)
    private String country;

    /** 合作等级：STRATEGIC / KEY / NORMAL */
    @Column(name = "coop_level", length = 16)
    private String coopLevel;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getOwnerTypeName() {
        return Dict.ownerTypeName(ownerType);
    }

    @Transient
    public String getCoopLevelName() {
        return Dict.coopLevelName(coopLevel);
    }
}
