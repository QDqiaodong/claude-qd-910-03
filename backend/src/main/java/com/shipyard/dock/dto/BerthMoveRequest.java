package com.shipyard.dock.dto;

import lombok.Data;

/**
 * 移泊入参：把船从原船台（或待排区）搬到目标船台。
 *
 * <p>fromBerthId 是「预期原船台」：服务端只有在船舶仍占着该船台时才会执行切换，
 * 为空表示船舶应在待排区。toBerthId 必填。</p>
 */
@Data
public class BerthMoveRequest {

    private Long vesselId;

    /** 预期原船台；为空表示从待排区排入 */
    private Long fromBerthId;

    /** 目标船台 */
    private Long toBerthId;

    /** 操作人（可选，留痕用） */
    private String operator;
}
