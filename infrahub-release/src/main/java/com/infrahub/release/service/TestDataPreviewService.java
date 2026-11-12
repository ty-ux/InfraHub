package com.infrahub.release.service;

import com.infrahub.release.dto.TestDataPreviewGroup;
import com.infrahub.release.dto.TestDataPreviewRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试数据治理预览服务
 * V1.8: 只读预览，不执行删除
 * 
 * 功能：
 * 1. V1.4/V1.5真实流水线部署证据链
 * 2. V1.6真实上传/发布证据链
 * 3. E2E临时数据
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestDataPreviewService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 获取测试数据预览
     * 返回三个分组：V1.4/V1.5证据链、V1.6证据链、E2E临时数据
     */
    public List<TestDataPreviewGroup> getTestDataPreview() {
        List<TestDataPreviewGroup> groups = new ArrayList<>();
        
        // Group 1: V1.4/V1.5真实流水线部署证据链
        groups.add(buildV14V15EvidenceGroup());
        
        // Group 2: V1.6真实上传/发布证据链
        groups.add(buildV16EvidenceGroup());
        
        // Group 3: E2E临时数据
        groups.add(buildE2ETempDataGroup());
        
        return groups;
    }

    /**
     * V1.4/V1.5真实流水线部署证据链
     * pipeline_def=88, release_order=89/90, pipeline_run=99/100, runtime_deploy_record=16/17
     */
    private TestDataPreviewGroup buildV14V15EvidenceGroup() {
        List<TestDataPreviewRecord> records = new ArrayList<>();
        
        // 查询pipeline_def=88
        records.addAll(queryPipelineDefByIds(List.of(88L)));
        
        // 查询release_order=89/90
        records.addAll(queryReleaseOrderByIds(List.of(89L, 90L)));
        
        // 查询pipeline_run=99/100
        records.addAll(queryPipelineRunByIds(List.of(99L, 100L)));
        
        // 查询runtime_deploy_record=16/17
        records.addAll(queryDeployRecordByIds(List.of(16L, 17L)));
        
        return TestDataPreviewGroup.builder()
                .groupName("V1.4/V1.5 真实流水线部署证据链")
                .description("Pipeline definition, release orders, and deployment records from V1.4/V1.5 real deployment tests")
                .records(records)
                .recommendedAction("建议保留 - 历史部署证据")
                .protectedEvidence(true)
                .build();
    }

    /**
     * V1.6真实上传/发布证据链
     * release_artifact=8/9, release_version=9/10, release_order=105/106, pipeline_run=114, runtime_deploy_record=18/19
     */
    private TestDataPreviewGroup buildV16EvidenceGroup() {
        List<TestDataPreviewRecord> records = new ArrayList<>();
        
        // 查询release_artifact=8/9
        records.addAll(queryArtifactByIds(List.of(8L, 9L)));
        
        // 查询release_version=9/10
        records.addAll(queryVersionByIds(List.of(9L, 10L)));
        
        // 查询release_order=105/106
        records.addAll(queryReleaseOrderByIds(List.of(105L, 106L)));
        
        // 查询pipeline_run=114
        records.addAll(queryPipelineRunByIds(List.of(114L)));
        
        // 查询runtime_deploy_record=18/19
        records.addAll(queryDeployRecordByIds(List.of(18L, 19L)));
        
        return TestDataPreviewGroup.builder()
                .groupName("V1.6 真实上传/发布证据链")
                .description("Release artifacts, versions, orders, and deployment records from V1.6 real upload/release tests")
                .records(records)
                .recommendedAction("建议保留 - 发布历史证据")
                .protectedEvidence(true)
                .build();
    }

    /**
     * E2E临时数据
     * pipeline_code like 'e2e-%', 'manual-unlinked-%', order_no like 'REL-%', 'REL-ACT-%', 'REL-PIPE-%', 'REL-PIPE-FAIL-%'
     */
    private TestDataPreviewGroup buildE2ETempDataGroup() {
        List<TestDataPreviewRecord> records = new ArrayList<>();
        
        // 查询E2E pipeline_def数据
        records.addAll(queryE2EPipelineDefs());
        
        // 查询E2E release_order数据
        records.addAll(queryE2EReleaseOrders());
        
        return TestDataPreviewGroup.builder()
                .groupName("E2E临时数据")
                .description("Temporary test data from E2E tests including pipeline codes starting with 'e2e-', 'manual-unlinked-%' and order numbers like 'REL-%'")
                .records(records)
                .recommendedAction("可清理 - E2E测试临时数据（本阶段仅预览，不执行删除）")
                .protectedEvidence(false)
                .build();
    }

    // ========== 查询方法 ==========

    private List<TestDataPreviewRecord> queryPipelineDefByIds(List<Long> ids) {
        String sql = "SELECT id, pipeline_code, pipeline_name, created_at FROM pipeline_def WHERE id IN (" + 
                String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new)) + ")";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName(rs.getString("pipeline_code"));
            record.setType("pipeline_def");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription(rs.getString("pipeline_name"));
            return record;
        });
    }

    private List<TestDataPreviewRecord> queryReleaseOrderByIds(List<Long> ids) {
        String sql = "SELECT id, order_no, status, created_at FROM release_order WHERE id IN (" + 
                String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new)) + ")";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName(rs.getString("order_no"));
            record.setType("release_order");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription("Status: " + rs.getString("status"));
            return record;
        });
    }

    private List<TestDataPreviewRecord> queryPipelineRunByIds(List<Long> ids) {
        String sql = "SELECT id, run_status, trigger_by, created_at FROM pipeline_run WHERE id IN (" + 
                String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new)) + ")";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName("Pipeline Run #" + rs.getLong("id"));
            record.setType("pipeline_run");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription("Status: " + rs.getString("run_status"));
            return record;
        });
    }

    private List<TestDataPreviewRecord> queryDeployRecordByIds(List<Long> ids) {
        String sql = "SELECT id, deploy_status, created_at FROM runtime_deploy_record WHERE id IN (" + 
                String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new)) + ")";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName("Deploy Record #" + rs.getLong("id"));
            record.setType("runtime_deploy_record");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription("Status: " + rs.getString("deploy_status"));
            return record;
        });
    }

    private List<TestDataPreviewRecord> queryArtifactByIds(List<Long> ids) {
        String sql = "SELECT id, artifact_name, artifact_type, created_at FROM release_artifact WHERE id IN (" + 
                String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new)) + ")";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName(rs.getString("artifact_name"));
            record.setType("release_artifact");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription("Type: " + rs.getString("artifact_type"));
            return record;
        });
    }

    private List<TestDataPreviewRecord> queryVersionByIds(List<Long> ids) {
        String sql = "SELECT id, version_no, app_id, created_at FROM release_version WHERE id IN (" + 
                String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new)) + ")";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName(rs.getString("version_no"));
            record.setType("release_version");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription("App ID: " + rs.getLong("app_id"));
            return record;
        });
    }

    private List<TestDataPreviewRecord> queryE2EPipelineDefs() {
        String sql = "SELECT id, pipeline_code, pipeline_name, created_at FROM pipeline_def " +
                "WHERE pipeline_code LIKE 'e2e-%' " +
                "OR pipeline_code LIKE 'manual-unlinked-%' " +
                "ORDER BY created_at DESC LIMIT 10";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName(rs.getString("pipeline_code"));
            record.setType("pipeline_def (E2E)");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription(rs.getString("pipeline_name"));
            return record;
        });
    }

    private List<TestDataPreviewRecord> queryE2EReleaseOrders() {
        String sql = "SELECT id, order_no, status, created_at FROM release_order " +
                "WHERE order_no LIKE 'REL-%' " +
                "OR order_no LIKE 'REL-ACT-%' " +
                "OR order_no LIKE 'REL-PIPE-%' " +
                "OR order_no LIKE 'REL-PIPE-FAIL-%' " +
                "ORDER BY created_at DESC LIMIT 10";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            TestDataPreviewRecord record = new TestDataPreviewRecord();
            record.setId(rs.getLong("id"));
            record.setName(rs.getString("order_no"));
            record.setType("release_order (E2E)");
            record.setCreatedAt(rs.getTimestamp("created_at") != null ? 
                    rs.getTimestamp("created_at").toLocalDateTime() : null);
            record.setDescription("Status: " + rs.getString("status"));
            return record;
        });
    }
}
