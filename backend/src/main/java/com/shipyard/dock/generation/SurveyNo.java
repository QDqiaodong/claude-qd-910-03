package com.shipyard.dock.generation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.hibernate.annotations.ValueGenerationType;

/**
 * 报验单号生成注解：挂在 {@code Survey.surveyNo} 上，插入时由 Hibernate 调
 * {@link SurveyNoGenerator} 生成 {@code SV-yyyyMMdd-序号} 形式的单号。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@ValueGenerationType(generatedBy = SurveyNoGenerator.class)
public @interface SurveyNo {

    /** 单号前缀 */
    String prefix() default "SV";

    /** 序号位数，不足补零 */
    int width() default 3;
}
