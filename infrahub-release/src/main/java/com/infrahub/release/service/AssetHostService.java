package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CreateAssetHostRequest;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.mapper.AssetHostMapper;
import com.infrahub.release.support.IpLiterals;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 主机台账服务。
 *
 * <p>手动登记只写入资产台账：主机状态与 Agent 状态一律以 UNKNOWN（待确认）入库，
 * 不声称在线、不声称健康，last_heartbeat 保持为空——登记不等于心跳，不臆造时间。
 */
@Service
@RequiredArgsConstructor
public class AssetHostService extends ServiceImpl<AssetHostMapper, AssetHost> {

    /** 新登记主机的初始状态：待确认，不代表在线也不代表故障 */
    public static final String INITIAL_STATUS_UNKNOWN = "UNKNOWN";

    private static final BigDecimal UNSPECIFIED_SPEC = BigDecimal.ZERO;

    /** 与 asset_host.cpu_cores NUMERIC(8,2) 对齐 */
    private static final BigDecimal CPU_CORES_MAX = new BigDecimal("999999.99");

    /** 与 asset_host.memory_gb / disk_gb NUMERIC(10,2) 对齐 */
    private static final BigDecimal CAPACITY_GB_MAX = new BigDecimal("99999999.99");

    private static final int MAX_SPEC_SCALE = 2;

    private final BaseEnvService baseEnvService;
    private final ResourcePoolService resourcePoolService;

    /**
     * 手动登记一台主机。
     *
     * @param request 登记请求（必填项由 Bean Validation 兜底）
     * @return 已落库的主机记录
     * @throws BizException 参数格式不合法、环境/资源池不存在或主机名/IP 重复
     */
    public AssetHost createHost(CreateAssetHostRequest request) {
        String hostname = trimToNull(request.getHostname());
        String ip = trimToNull(request.getIp());
        String osType = trimToNull(request.getOsType());
        String hostType = trimToNull(request.getHostType());

        if (hostname == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "主机名不能为空");
        }
        if (ip == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "IP 地址不能为空");
        }
        if (osType == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "系统类型不能为空");
        }
        if (hostType == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "主机类型不能为空");
        }
        if (hostname.length() > 128) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "主机名长度不能超过 128 个字符");
        }
        if (ip.length() > 64) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "IP 地址长度不能超过 64 个字符");
        }
        if (osType.length() > 32) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "系统类型长度不能超过 32 个字符");
        }
        if (hostType.length() > 32) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "主机类型长度不能超过 32 个字符");
        }
        if (!IpLiterals.isIpLiteral(ip)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "IP 地址格式不正确，仅支持 IPv4 或 IPv6 字面量: " + ip);
        }

        // 规格入库前再挡一次：不依赖数据库的精度/范围报错，也不把非法值静默折成"未填写"
        requireSpecInRange("CPU 核数", request.getCpuCores(), CPU_CORES_MAX);
        requireSpecInRange("内存容量", request.getMemoryGb(), CAPACITY_GB_MAX);
        requireSpecInRange("磁盘容量", request.getDiskGb(), CAPACITY_GB_MAX);

        if (request.getEnvId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "环境不能为空");
        }
        BaseEnv env = baseEnvService.getById(request.getEnvId());
        if (env == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "环境不存在: " + request.getEnvId());
        }
        if (request.getResourcePoolId() != null) {
            ResourcePool pool = resourcePoolService.getById(request.getResourcePoolId());
            if (pool == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "资源池不存在: " + request.getResourcePoolId());
            }
        }

        requireHostnameAvailable(hostname);
        requireIpAvailable(ip);

        AssetHost host = new AssetHost();
        host.setHostname(hostname);
        host.setIp(ip);
        host.setOsType(osType);
        host.setHostType(hostType);
        host.setEnvId(request.getEnvId());
        host.setResourcePoolId(request.getResourcePoolId());
        host.setCpuCores(specOrDefault(request.getCpuCores()));
        host.setMemoryGb(specOrDefault(request.getMemoryGb()));
        host.setDiskGb(specOrDefault(request.getDiskGb()));
        host.setHostStatus(INITIAL_STATUS_UNKNOWN);
        host.setAgentStatus(INITIAL_STATUS_UNKNOWN);
        // last_heartbeat 保持 null：登记不等于心跳

        try {
            baseMapper.insert(host);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求可能同时通过前置查重，最终由唯一约束兜底
            throw new BizException(ErrorCode.VALIDATION_ERROR, "主机名或 IP 已存在，登记被拒绝");
        }
        return host;
    }

    private void requireHostnameAvailable(String hostname) {
        LambdaQueryWrapper<AssetHost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetHost::getHostname, hostname);
        if (baseMapper.selectCount(wrapper) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "主机名已存在: " + hostname);
        }
    }

    private void requireIpAvailable(String ip) {
        LambdaQueryWrapper<AssetHost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AssetHost::getIp, ip);
        if (baseMapper.selectCount(wrapper) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "IP 地址已存在: " + ip);
        }
    }

    private static BigDecimal specOrDefault(BigDecimal value) {
        return value != null ? value : UNSPECIFIED_SPEC;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 规格的范围与精度校验，与 {@code CreateAssetHostRequest} 上的注解同一套规则。
     *
     * <p>留空（null）表示"未登记规格"，放行并由 {@link #specOrDefault} 落成 0；
     * 一旦填写就必须是明确的正数，且与库表列宽一致。这里显式报参数校验错误，
     * 而不是让数据库以精度/范围错误兜底（那是 500，不是校验错误）。
     */
    private static void requireSpecInRange(String label, BigDecimal value, BigDecimal max) {
        if (value == null) {
            return;
        }
        if (value.signum() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, label + "必须大于 0");
        }
        if (value.scale() > MAX_SPEC_SCALE) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, label + "最多 2 位小数");
        }
        if (value.compareTo(max) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, label + "不能超过 " + max.toPlainString());
        }
    }
}
