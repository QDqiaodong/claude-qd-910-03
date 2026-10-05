package com.shipyard.dock.generation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.hibernate.annotations.ValueGenerationType;

/**
 * 分段编码生成注解：挂在 {@code Block.blockCode} 上，插入时由 Hibernate 调
 * {@link SegmentCodeGenerator} 生成 {@code SEG-船号-序号} 形式的编码。
 *
 * <p>这是本仓的后端技术路线：Hibernate {@code @ValueGenerationType} 自定义注解式字段值生成，
 * 值在 INSERT 语句执行之前（Java 侧）生成，序号取自 {@code code_sequence} 表。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@ValueGenerationType(generatedBy = SegmentCodeGenerator.class)
public @interface SegmentCode {

    /** 编码前缀 */
    String prefix() default "SEG";

    /** 序号位数，不足补零 */
    int width() default 3;
}
