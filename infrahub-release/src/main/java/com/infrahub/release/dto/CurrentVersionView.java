package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseCurrentVersion;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 版本指针的只读视图：指针本身 + **本次执行覆盖范围** + **当前登记版本一致性**。
 *
 * <p>字段与 {@link ReleaseCurrentVersion} 一一对应（名称不变），另加两个互相独立的事实：
 * {@link DeployCoverage}（当时这次执行覆盖了哪些实例）与
 * {@link VersionRegistrationConsistency}（现在当前实例分别登记在什么版本、是否等于指针版本）。
 * 消费方按字段名读取，因此加字段不改变既有读路径的可用性。
 *
 * <p>为什么必须一起返回：指针是 (服务, 环境) 一行，只记录“最近一次成功发布的版本号”；
 * 实例版本是逐实例的。只给指针，页面就有可能把“一部分实例已部署”表述成
 * “这个服务/环境已全部部署到该版本”，或者把历史覆盖说成当前一致。
 */
@Data
public class CurrentVersionView {

    private Long id;
    private Long envId;
    private Long serviceId;
    private Long versionId;
    private Long sourceReleaseOrderId;
    private Long sourceRollbackOrderId;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime updatedAt;

    /** 该指针这次版本变更的实例覆盖范围（可能不可证明，见 {@code verdict}）。 */
    private DeployCoverage coverage;

    /** 当前实例登记版本与指针目标版本的一致性（与覆盖范围是两件事）。 */
    private VersionRegistrationConsistency registrationConsistency;

    public static CurrentVersionView of(ReleaseCurrentVersion pointer, DeployCoverage coverage,
                                        VersionRegistrationConsistency registrationConsistency) {
        CurrentVersionView view = new CurrentVersionView();
        if (pointer != null) {
            view.setId(pointer.getId());
            view.setEnvId(pointer.getEnvId());
            view.setServiceId(pointer.getServiceId());
            view.setVersionId(pointer.getVersionId());
            view.setSourceReleaseOrderId(pointer.getSourceReleaseOrderId());
            view.setSourceRollbackOrderId(pointer.getSourceRollbackOrderId());
            view.setConfirmedAt(pointer.getConfirmedAt());
            view.setUpdatedAt(pointer.getUpdatedAt());
        }
        view.setCoverage(coverage);
        view.setRegistrationConsistency(registrationConsistency);
        return view;
    }
}
