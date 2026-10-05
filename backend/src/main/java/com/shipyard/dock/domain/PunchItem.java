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
import lombok.Getter;
import lombok.Setter;

/**
 * 遗留项（punch list）：报验判不合格 / 让步时开出来的整改条目。
 *
 * <p>闭环（整改完成）前，所属分段不允许置为已交验。</p>
 */
@Entity
@Table(name = "punch_item")
@Getter
@Setter
public class PunchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 遗留项编号，唯一 */
    @Column(name = "punch_no", nullable = false, unique = true, length = 24)
    private String punchNo;

    @Column(name = "survey_id")
    private Long surveyId;

    @Column(name = "survey_no", length = 24)
    private String surveyNo;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "block_code", length = 24)
    private String blockCode;

    @Column(name = "title", length = 64)
    private String title;

    @Column(name = "description", length = 128)
    private String description;

    /** 严重度：MINOR / MAJOR / CRITICAL */
    @Column(name = "severity", length = 16)
    private String severity;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 整改责任人 */
    @Column(name = "owner", length = 32)
    private String owner;

    /** 状态：OPEN / RECTIFYING / CLOSED */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getSeverityName() {
        return Dict.punchSeverityName(severity);
    }

    @Transient
    public String getStatusName() {
        return Dict.punchStatusName(status);
    }

    @Transient
    public boolean isClosed() {
        return "CLOSED".equals(status);
    }

    /** 超期未闭环 */
    @Transient
    public boolean isOverdue() {
        return !isClosed() && dueDate != null && dueDate.isBefore(LocalDate.now());
    }
}
