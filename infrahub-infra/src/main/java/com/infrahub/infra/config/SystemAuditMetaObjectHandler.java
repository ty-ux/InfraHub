package com.infrahub.infra.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.infrahub.common.context.UserContextHolder;
import org.apache.ibatis.reflection.MetaObject;

import java.time.OffsetDateTime;

public class SystemAuditMetaObjectHandler implements MetaObjectHandler {

    private static final Long SYSTEM_USER_ID = 1L;

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        Long userId = UserContextHolder.getUserIdOrDefault(SYSTEM_USER_ID);
        strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "createdBy", Long.class, userId);
        strictInsertFill(metaObject, "updatedBy", Long.class, userId);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
        strictUpdateFill(metaObject, "updatedBy", Long.class, UserContextHolder.getUserIdOrDefault(SYSTEM_USER_ID));
    }
}
