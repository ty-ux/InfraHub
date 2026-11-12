package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.RuntimeServiceInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RuntimeServiceInstanceMapper extends BaseMapper<RuntimeServiceInstance> {

    /**
     * 锁定并读回实例整行（{@code SELECT * ... FOR UPDATE}），用于"移除登记"与"监控配置写入"的
     * 共同协调。
     *
     * <p>为什么不能先 {@code selectById} 再 {@code getById}：同一事务内 MyBatis 一级缓存是 SESSION 级、
     * 以 Statement + 参数为键，先前的快照读已经把旧对象放进缓存；加锁后再次 {@code selectById}
     * 会直接命中缓存返回旧对象，"锁拿到了、读到的却是锁前的状态"。本方法用独立语句并显式
     * {@code flushCache = true} + {@code useCache = false}：先清掉快照读留下的缓存条目，
     * 再保证锁定读本身永远落到数据库。
     *
     * <p>必须在真实生效的事务内调用，否则行锁随自动提交立即释放。
     *
     * @return 实例整行；行不存在时返回 null（调用方据此拒绝）
     */
    @Select("SELECT * FROM runtime_service_instance WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    RuntimeServiceInstance selectFullForUpdate(@Param("id") Long id);

    /**
     * 写回开始处的**行锁读**：读回实例当前身份与所属主机，并独占该行直到事务结束。
     *
     * <p>与 {@link AssetHostMapper#selectForUpdate} 同构：
     * 一是让调用方比对"关联主机 / 实例类型 / 服务 / 环境"是否仍是采样时那一份
     * （重新绑定或改绑服务后旧样本必须被拒收），二是让同一个目标的写回跨 JVM 串行化。
     *
     * <p>投影必须**包含调用方要比对的每一个身份字段**：{@code service_id} 与 {@code env_id}
     * 决定这次探测用的是哪一份诊断参数，它们不在这条 SELECT 里时读回来恒为 null，
     * 比对就会对**每一个**实例样本恒不成立 —— 那是"永远拒收"，不是保守。
     *
     * @return 实例行；已被删除时返回 null（调用方据此拒收样本）
     */
    @Select("""
            SELECT id, service_id, env_id, host_id, instance_type, instance_status, health_status
            FROM runtime_service_instance
            WHERE id = #{id}
            FOR UPDATE
            """)
    @Options(useCache = false)
    RuntimeServiceInstance selectForUpdate(@Param("id") Long id);

    /**
     * 采集成功时写入观测到的运行与健康状态。**只改 instance_status / health_status / updated_at**，
     * 不触碰 instance_type、host_id、version_id 等身份与部署字段，也不触碰 last_heartbeat。
     *
     * <p>运行与健康是正交的两个维度：进程在跑而健康检查失败（RUNNING + DOWN）是合法组合。
     *
     * @return 受影响行数（实例已被删除时为 0）
     */
    @Update("""
            UPDATE runtime_service_instance
            SET instance_status = #{instanceStatus}, health_status = #{healthStatus}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateObservedStatus(@Param("id") Long id,
                             @Param("instanceStatus") String instanceStatus,
                             @Param("healthStatus") String healthStatus);

    /**
     * 失败达阈值 / 证据过期时把实例状态退回"无法确认"，两个维度各自独立判定。
     *
     * <p>**条件更新**：某一维度只有当库里的值仍是采集上次写入的值时才改写，另一维度不受影响。
     * 若两个维度都已被他方改写，则整行不动。
     *
     * @return 受影响行数（0 表示无可改写维度）
     */
    @Update("""
            UPDATE runtime_service_instance
            SET instance_status = CASE
                    WHEN instance_status = #{expectedStatus} THEN #{newStatus}
                    ELSE instance_status
                END,
                health_status = CASE
                    WHEN health_status = #{expectedHealth} THEN #{newHealth}
                    ELSE health_status
                END,
                updated_at = NOW()
            WHERE id = #{id}
              AND (instance_status = #{expectedStatus} OR health_status = #{expectedHealth})
            """)
    int resetObservedStatusIfMatched(@Param("id") Long id,
                                     @Param("expectedStatus") String expectedStatus,
                                     @Param("expectedHealth") String expectedHealth,
                                     @Param("newStatus") String newStatus,
                                     @Param("newHealth") String newHealth);
}