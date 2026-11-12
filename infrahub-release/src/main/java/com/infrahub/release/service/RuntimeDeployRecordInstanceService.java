package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeDeployRecordInstance;
import com.infrahub.release.mapper.RuntimeDeployRecordInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部署记录与**实例身份**的关联（{@code runtime_deploy_record_instance}）。
 *
 * <p><b>为什么需要</b>：部署记录只带服务/环境/主机，不带实例。流水线成功回调原先按服务+环境
 * 重新枚举实例并更新版本，会把执行期间新增、实际并未部署的实例也算进去。本服务把
 * “这条记录部署的是哪个实例”变成创建时就写下的持久事实，成功方向只据此更新版本。
 *
 * <p><b>写入时机</b>：随记录插入的同一个短事务（{@link RuntimeDeployRecordService#startRunning}）。
 * 关联不存在事后回填，历史记录不做事后推断。
 *
 * <p><b>失败关闭</b>：记录带有实例身份但表不可用（V34 未应用）时拒绝执行，而不是带着
 * “归属未知”的记录去调用远端。缺少这张表意味着平台无法产出任何可证明的实例归属，
 * 放行只会让每次发布都停在“无法采纳成功”的状态。
 *
 * <p><b>只读路径不因此失效</b>：表不存在时读取返回空集合，由调用方按“无法证明”处理；
 * 普通旧库上的部署记录读取不受影响（本表是新增表，没有给既有表加列）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeDeployRecordInstanceService {

    private static final String MESSAGE_TABLE_MISSING =
            "部署记录与实例身份的关联需要数据库表 runtime_deploy_record_instance（V34 迁移未应用）；"
                    + "已按失败关闭拒绝执行。请由数据库负责人应用 V34 并重启服务后再操作";

    private final RuntimeDeployRecordInstanceMapper instanceMapper;

    /** 迁移是否已经应用；只在第一次查询时探测并缓存（与 V31/V32 同样的负缓存约定）。 */
    private volatile Boolean tableAvailable;

    /**
     * 把**刚刚创建的这一条**部署记录绑定到它实际部署的实例。
     *
     * <p>必须在记录插入的同一短事务内调用：绑定失败会让整个短事务回滚（RUNNING 记录一起消失），
     * 因此不存在“记录已存在、实例归属未知”的中间态。
     *
     * <p>记录不带实例身份时直接返回：非发布执行路径（如回滚记录）不写这张表，
     * 读取方会把“没有关联”当作**无法证明**，不会当成“不需要归属”。是否带上实例身份
     * 由记录创建方决定，本方法不猜测。
     *
     * @throws BizException 记录带实例身份但表未就绪，或归属写入未生效
     */
    public void bindRecordToInstance(RuntimeDeployRecord record) {
        if (record == null || record.getInstanceId() == null) {
            return;
        }
        if (record.getId() == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "部署记录未返回身份，无法绑定实例归属，已拒绝继续执行");
        }
        if (!tableAvailable()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, MESSAGE_TABLE_MISSING);
        }

        RuntimeDeployRecordInstance row = new RuntimeDeployRecordInstance();
        row.setDeployRecordId(record.getId());
        row.setInstanceId(record.getInstanceId());
        row.setServiceId(record.getServiceId());
        row.setEnvId(record.getEnvId());
        row.setHostId(record.getHostId());
        row.setInstanceName(record.getInstanceName());
        row.setInstanceType(record.getInstanceType());
        if (instanceMapper.insertIfAbsent(row) != 1) {
            // 刚插入的行不可能已经有归属：说明这条记录的实例归属存疑，必须整笔回滚。
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "部署记录 " + record.getId() + " 的实例归属写入未生效，已拒绝继续执行");
        }
    }

    /** 表是否可用（V34 已应用）。 */
    public boolean isTableAvailable() {
        return tableAvailable();
    }

    /**
     * 一批部署记录的实例归属。表未就绪或 {@code deployRecordIds} 为空时返回空集合——
     * 调用方必须把“没有关联行”当作**无法证明**，不能当作“无实例”。
     */
    public List<RuntimeDeployRecordInstance> listByDeployRecordIds(List<Long> deployRecordIds) {
        if (!tableAvailable() || deployRecordIds == null || deployRecordIds.isEmpty()) {
            return List.of();
        }
        return instanceMapper.listByDeployRecordIds(deployRecordIds);
    }

    /** 单条记录的实例归属；无关联行或表未就绪时返回 null。 */
    public RuntimeDeployRecordInstance findByDeployRecordId(Long deployRecordId) {
        if (!tableAvailable() || deployRecordId == null) {
            return null;
        }
        return instanceMapper.selectByDeployRecordId(deployRecordId);
    }

    /** 某实例的部署归属历史（只读核对）。表未就绪时返回空集合。 */
    public List<RuntimeDeployRecordInstance> listByInstanceId(Long instanceId) {
        if (!tableAvailable() || instanceId == null) {
            return List.of();
        }
        return instanceMapper.listByInstanceId(instanceId);
    }

    private boolean tableAvailable() {
        Boolean cached = tableAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (tableAvailable != null) {
                return tableAvailable;
            }
            tableAvailable = instanceMapper.probe() != null;
            if (!tableAvailable) {
                log.error("数据库表 runtime_deploy_record_instance 不存在（V34 迁移未应用）；"
                        + "部署执行与实例归属核对已按失败关闭拒绝，直到迁移应用并重启服务");
            }
            return tableAvailable;
        }
    }
}
