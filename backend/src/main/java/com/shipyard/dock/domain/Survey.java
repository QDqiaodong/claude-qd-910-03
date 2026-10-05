package com.shipyard.dock.domain;

import java.time.LocalDateTime;

import com.shipyard.dock.common.Dict;
import com.shipyard.dock.generation.SurveyNo;

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
 * 报验单：船东 / 船检对分段或舱室的验收记录。
 *
 * <p>报验单号 {@code surveyNo} 由 {@link SurveyNo} 注解 +
 * Hibernate {@code @ValueGenerationType} 在插入前自动生成，格式 {@code SV-yyyyMMdd-序号}。</p>
 *
 * <p>结论三选一：合格 / 让步 / 不合格；判不合格必须至少开一条遗留项。</p>
 */
@Entity
@Table(name = "survey")
@Getter
@Setter
public class Survey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报验单号，插入时由生成器生成，格式 SV-yyyyMMdd-序号 */
    @SurveyNo(prefix = "SV", width = 3)
    @Column(name = "survey_no", nullable = false, unique = true, length = 24)
    private String surveyNo;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "block_code", length = 24)
    private String blockCode;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    /** 报验项目：WELD / DIMENSION / COATING / OUTFITTING / TIGHTNESS */
    @Column(name = "survey_item", length = 16)
    private String surveyItem;

    @Column(name = "survey_date")
    private LocalDateTime surveyDate;

    /** 检验机构：CCS / ABS / LR / DNV / BV / OWNER */
    @Column(name = "authority", length = 16)
    private String authority;

    @Column(name = "inspector", length = 32)
    private String inspector;

    /** 结论：PASS 合格 / CONCESSION 让步 / FAIL 不合格 */
    @Column(name = "result", length = 16)
    private String result;

    @Column(name = "remark", length = 64)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getSurveyItemName() {
        return Dict.surveyItemName(surveyItem);
    }

    @Transient
    public String getAuthorityName() {
        return Dict.authorityName(authority);
    }

    @Transient
    public String getResultName() {
        return Dict.surveyResultName(result);
    }

    @Transient
    public boolean isPass() {
        return "PASS".equals(result);
    }

    @Transient
    public boolean isFail() {
        return "FAIL".equals(result);
    }

    /** 判定印章：合格 / 让步 / 不合格 */
    @Transient
    public String getStamp() {
        if ("PASS".equals(result)) {
            return "合格";
        }
        if ("CONCESSION".equals(result)) {
            return "让步";
        }
        if ("FAIL".equals(result)) {
            return "不合格";
        }
        return "待定";
    }
}
