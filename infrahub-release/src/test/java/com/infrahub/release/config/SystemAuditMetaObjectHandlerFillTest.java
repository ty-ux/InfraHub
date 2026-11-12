package com.infrahub.release.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.infrahub.infra.config.SystemAuditMetaObjectHandler;
import com.infrahub.release.entity.ReleasePrecheckRecord;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 JVM 测试：验证 SystemAuditMetaObjectHandler.insertFill 真实填充
 * ReleasePrecheckRecord.createdAt，且类型为 OffsetDateTime。
 * 不启动 Spring 容器，不连接真实 DB/Docker/SSH/Kubernetes。
 */
class SystemAuditMetaObjectHandlerFillTest {

    @Test
    @DisplayName("insertFill 真实填充 createdAt 且类型为 OffsetDateTime")
    void insertFillPopulatesCreatedAtAsOffsetDateTime() {
        // 注册实体 TableInfo，使 strictInsertFill 的类型匹配检查真实生效
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ReleasePrecheckRecord.class);

        ReleasePrecheckRecord record = new ReleasePrecheckRecord();
        record.setBizType("RELEASE_ORDER");
        record.setBizId(1L);
        record.setEnvId(2L);
        record.setServiceId(3L);
        record.setSuccess(true);
        record.setSummary("前置检查实例 1/1 通过；失败 0；首条失败原因: 无");

        MetaObject metaObject = SystemMetaObject.forObject(record);
        new SystemAuditMetaObjectHandler().insertFill(metaObject);

        assertNotNull(record.getCreatedAt(), "createdAt 必须被 MetaObjectHandler 填充，不能为 null");
        assertTrue(record.getCreatedAt() instanceof OffsetDateTime,
                "createdAt 类型必须为 OffsetDateTime，当前: " + record.getCreatedAt().getClass().getName());
        assertTrue(record.getCreatedAt().isAfter(OffsetDateTime.now().minusMinutes(1)),
                "填充时间应为当前时刻附近");
    }
}