package com.shipyard.dock.generation;

import java.lang.reflect.Member;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.AnnotationBasedGenerator;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.GeneratorCreationContext;
import org.springframework.util.StringUtils;

import com.shipyard.dock.domain.Survey;

/**
 * {@link SurveyNo} 的生成器：插入前为报验单生成 {@code SV-yyyyMMdd-序号}。
 *
 * <p>日期取自同一实体的 {@code surveyDate}（为空则取当天），序号按「日期」分键自增。</p>
 */
public class SurveyNoGenerator implements BeforeExecutionGenerator, AnnotationBasedGenerator<SurveyNo> {

    private static final long serialVersionUID = 1L;

    /** 序号键：报验单号 */
    public static final String SEQ_KEY = "SURVEY_NO";

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    private String prefix = "SV";
    private int width = 3;

    public SurveyNoGenerator() {
    }

    public SurveyNoGenerator(SurveyNo annotation, Member member, GeneratorCreationContext context) {
        initialize(annotation, member, context);
    }

    @Override
    public void initialize(SurveyNo annotation, Member member, GeneratorCreationContext context) {
        this.prefix = annotation.prefix();
        this.width = Math.max(1, annotation.width());
    }

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
            EventType eventType) {
        if (eventType != EventType.INSERT) {
            return currentValue;
        }
        if (currentValue != null && StringUtils.hasText(currentValue.toString())) {
            return currentValue;
        }
        if (!(owner instanceof Survey survey)) {
            return currentValue;
        }
        LocalDateTime date = survey.getSurveyDate() == null ? LocalDateTime.now() : survey.getSurveyDate();
        String scope = date.format(DATE_PART);
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
