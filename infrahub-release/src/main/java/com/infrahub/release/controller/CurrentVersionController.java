package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.CurrentVersionView;
import com.infrahub.release.dto.DeployCoverage;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.service.ReleaseCurrentVersionService;
import com.infrahub.release.service.ReleaseDeployCoverageService;
import com.infrahub.release.service.RuntimeDeployRecordService;
import com.infrahub.release.service.VersionRegistrationConsistencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/current-versions")
@PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
public class CurrentVersionController {

    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final ReleaseDeployCoverageService releaseDeployCoverageService;
    private final VersionRegistrationConsistencyService versionRegistrationConsistencyService;

    /**
     * 版本指针列表，每行都带上两个互相独立的事实：
     * <ul>
     *   <li>{@code coverage}：这次版本变更的成功执行覆盖了哪些实例；</li>
     *   <li>{@code registrationConsistency}：当前实例分别登记在什么版本、是否都等于指针版本。</li>
     * </ul>
     *
     * <p>两者不能互相替代：{@code coverage.verdict=FULL} 不代表当前登记版本仍一致；
     * 只有 {@code registrationConsistency.status=ALL_MATCH} 才表示当前全部实例在平台登记上
     * 都是目标版本，且它仍不是远端实际运行版本的探测结果。
     *
     * <p>代价：每行要按来源订单核对执行证据，并逐个实例比较登记版本，是只读的管理页查询，
     * 不持锁。页大小上限 200，行数越多查询越多；需要更大的页面时应在服务层做成批查询。
     */
    @GetMapping
    public ApiResponse<PageResponse<CurrentVersionView>> page(@Valid PageQuery query) {
        Page<ReleaseCurrentVersion> page =
                releaseCurrentVersionService.page(new Page<>(query.getPageNum(), query.getPageSize()));
        List<CurrentVersionView> rows = page.getRecords().stream()
                .map(pointer -> CurrentVersionView.of(pointer,
                        releaseDeployCoverageService.forVersionPointer(pointer),
                        versionRegistrationConsistencyService.forVersionPointer(pointer)))
                .toList();
        PageResponse<CurrentVersionView> response = PageResponse.<CurrentVersionView>builder()
                .pageNum(page.getCurrent())
                .pageSize(page.getSize())
                .total(page.getTotal())
                .records(rows)
                .build();
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /** 单行版本指针的覆盖范围（只读）。指针不存在时返回“没有可归属的执行”而不是空响应。 */
    @GetMapping("/{id}/coverage")
    public ApiResponse<DeployCoverage> coverage(@PathVariable Long id) {
        ReleaseCurrentVersion pointer = releaseCurrentVersionService.getById(id);
        return ApiResponse.success(
                releaseDeployCoverageService.forVersionPointer(pointer), TraceIdHolder.get());
    }

    @GetMapping("/{id}/latest-deploy-records")
    public ApiResponse<List<RuntimeDeployRecord>> latestDeployRecords(@PathVariable Long id) {
        ReleaseCurrentVersion cv = releaseCurrentVersionService.getById(id);
        if (cv == null) {
            return ApiResponse.success(List.of(), TraceIdHolder.get());
        }
        List<RuntimeDeployRecord> records = new ArrayList<>();
        // 回滚成功后指针可能同时保留旧的发布单来源；回滚是最新一次版本变更，不能把两批记录混在一起。
        if (cv.getSourceRollbackOrderId() != null) {
            records.addAll(runtimeDeployRecordService.listByRollbackOrderId(cv.getSourceRollbackOrderId()));
        } else if (cv.getSourceReleaseOrderId() != null) {
            records.addAll(runtimeDeployRecordService.listByReleaseOrderId(cv.getSourceReleaseOrderId()));
        }
        records.sort((a, b) -> {
            if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });
        return ApiResponse.success(records, TraceIdHolder.get());
    }
}
