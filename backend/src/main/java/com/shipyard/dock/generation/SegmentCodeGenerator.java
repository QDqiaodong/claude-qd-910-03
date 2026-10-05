package com.shipyard.dock.generation;

import java.lang.reflect.Member;
import java.util.EnumSet;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.AnnotationBasedGenerator;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.GeneratorCreationContext;
import org.springframework.util.StringUtils;

import com.shipyard.dock.domain.Block;

/**
 * {@link SegmentCode} 的生成器：插入前为分段生成 {@code SEG-船号-序号}。
 *
 * <p>生成器能读到同一实体的其它字段（这里读 {@code vesselNo}）来拼码，序号按「船号」分键自增。</p>
 */
public class SegmentCodeGenerator implements BeforeExecutionGenerator, AnnotationBasedGenerator<SegmentCode> {

    private static final long serialVersionUID = 1L;

    /** 序号键：分段编码 */
    public static final String SEQ_KEY = "SEGMENT_CODE";

    private String prefix = "SEG";
    private int width = 3;

    public SegmentCodeGenerator() {
    }

    public SegmentCodeGenerator(SegmentCode annotation, Member member, GeneratorCreationContext context) {
        initialize(annotation, member, context);
    }

    @Override
    public void initialize(SegmentCode annotation, Member member, GeneratorCreationContext context) {
        this.prefix = annotation.prefix();
        this.width = Math.max(1, annotation.width());
    }

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
            EventType eventType) {
        // 只在插入时生成；更新时保持原值，避免把已经编好的号刷掉
        if (eventType != EventType.INSERT) {
            return currentValue;
        }
        if (currentValue != null && StringUtils.hasText(currentValue.toString())) {
            return currentValue;
        }
        if (!(owner instanceof Block block)) {
            return currentValue;
        }
        String vesselNo = block.getVesselNo();
        String scope = StringUtils.hasText(vesselNo) ? vesselNo : "COMMON";
        long seq = CodeSequenceService.next(SEQ_KEY, scope);
        return prefix + "-" + scope + "-" + String.format("%0" + width + "d", seq);
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EnumSet.of(EventType.INSERT);
    }

    @Override
    public boolean generatedOnExecution() {
        return false;
    }
}
