package com.infrahub.release.service;

import com.infrahub.release.dto.DatabaseSequenceHealth;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DatabaseSequenceHealthService {

    private static final List<SequenceTarget> DEFAULT_TARGETS = List.of(
            new SequenceTarget("release_artifact", "release_artifact_id_seq"),
            new SequenceTarget("release_version", "release_version_id_seq"),
            new SequenceTarget("release_order", "release_order_id_seq"),
            new SequenceTarget("release_rollback_order", "release_rollback_order_id_seq"),
            new SequenceTarget("runtime_deploy_record", "runtime_deploy_record_id_seq"),
            new SequenceTarget("pipeline_run", "pipeline_run_id_seq"),
            new SequenceTarget("pipeline_step_run", "pipeline_step_run_id_seq")
    );

    private final JdbcTemplate jdbcTemplate;

    public List<DatabaseSequenceHealth> checkDefaultSequences() {
        return checkSequences(DEFAULT_TARGETS);
    }

    List<DatabaseSequenceHealth> checkSequences(List<SequenceTarget> targets) {
        return targets.stream()
                .map(this::checkSequence)
                .toList();
    }

    private DatabaseSequenceHealth checkSequence(SequenceTarget target) {
        Long maxId = Objects.requireNonNullElse(jdbcTemplate.queryForObject(
                "select coalesce(max(id), 0) from " + target.tableName(),
                Long.class
        ), 0L);
        Long sequenceValue = Objects.requireNonNullElse(jdbcTemplate.queryForObject(
                "select last_value from " + target.sequenceName(),
                Long.class
        ), 0L);
        boolean healthy = sequenceValue >= maxId;

        DatabaseSequenceHealth health = new DatabaseSequenceHealth();
        health.setTableName(target.tableName());
        health.setSequenceName(target.sequenceName());
        health.setMaxId(maxId);
        health.setSequenceValue(sequenceValue);
        health.setHealthy(healthy);
        if (!healthy) {
            health.setSuggestedFixSql("select setval('" + target.sequenceName()
                    + "', (select coalesce(max(id), 0) + 1 from " + target.tableName() + "), false);");
        }
        return health;
    }

    record SequenceTarget(String tableName, String sequenceName) {
    }
}
