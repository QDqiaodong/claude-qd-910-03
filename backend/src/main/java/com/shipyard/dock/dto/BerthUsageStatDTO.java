package com.shipyard.dock.dto;

import com.shipyard.dock.common.Dict;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ①：按船台类型统计数量、占用数、平均长度与平均承载能力。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BerthUsageStatDTO {

    private String berthType;
    private String berthTypeName;
    private long total;
    private long occupied;
    private double avgLength;
    private double avgCapacity;

    public BerthUsageStatDTO(String berthType, Long total, Long occupied, Double avgLength, Double avgCapacity) {
        this(berthType, Dict.berthTypeName(berthType),
                total == null ? 0L : total,
                occupied == null ? 0L : occupied,
                avgLength == null ? 0.0 : round1(avgLength),
                avgCapacity == null ? 0.0 : round1(avgCapacity));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
