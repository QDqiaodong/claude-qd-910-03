package com.shipyard.dock.dto;

import com.shipyard.dock.common.Dict;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ④：按报验结论统计单数与让步/不合格占比。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurveyVerdictStatDTO {

    private String result;
    private String resultName;
    private long count;

    public SurveyVerdictStatDTO(String result, Long count) {
        this(result, Dict.surveyResultName(result), count == null ? 0L : count);
    }
}
