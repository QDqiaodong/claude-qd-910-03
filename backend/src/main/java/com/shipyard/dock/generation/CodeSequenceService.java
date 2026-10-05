package com.shipyard.dock.generation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 业务编码自增序号来源。
 *
 * <p>本仓的「注解式字段值生成」路线（Hibernate {@code @ValueGenerationType} +
 * {@code BeforeExecutionGenerator}）需要在插入前拿到一个自增序号，序号放在
 * {@code code_sequence} 表里按 {@code seq_key + seq_scope} 分键自增。</p>
 *
 * <p>生成器实例由 Hibernate 自己 new 出来，拿不到 Spring 容器，因此这里用一个静态引用把
 * Spring 管理的单例暴露给生成器（构造期赋值，容器里只有一个实例）。</p>
 */
@Service
public class CodeSequenceService {

    private static CodeSequenceService instance;

    private final JdbcTemplate jdbcTemplate;

    public CodeSequenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        instance = this;
    }

    /** 生成器入口：取下一个序号（容器还没起来时退化为 1，保证不会 NPE） */
    public static long next(String key, String scope) {
        if (instance == null) {
            return 1L;
        }
        return instance.nextValue(key, scope);
    }

    /**
     * 按 key + scope 自增取值：不存在则插入 1，存在则 +1。
     * 走 INSERT ... ON DUPLICATE KEY UPDATE，天然保证并发下不重复。
     */
    public long nextValue(String key, String scope) {
        String scopeKey = scope == null || scope.isBlank() ? "COMMON" : scope;
        jdbcTemplate.update(
                "INSERT INTO code_sequence(seq_key, seq_scope, seq_value, updated_at) "
                        + "VALUES (?, ?, 1, NOW()) "
                        + "ON DUPLICATE KEY UPDATE seq_value = seq_value + 1, updated_at = NOW()",
                key, scopeKey);
        Long value = jdbcTemplate.queryForObject(
                "SELECT seq_value FROM code_sequence WHERE seq_key = ? AND seq_scope = ?",
                Long.class, key, scopeKey);
        return value == null ? 1L : value;
    }
}
