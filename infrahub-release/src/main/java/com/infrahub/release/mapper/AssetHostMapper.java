package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.AssetHost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AssetHostMapper extends BaseMapper<AssetHost> {

    /** Full locking read for SSH setup and environment-safe instance registration. */
    @Select("SELECT * FROM asset_host WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    AssetHost selectFullForSetup(@Param("id") Long id);


    /**
     * 写回开始处的**行锁读**：读回主机当前身份与地址，并独占该行直到事务结束。
     *
     * <p>两个作用，缺一不可：
     * <ol>
     *   <li>拿到写回时刻的 hostname / ip，供调用方与采样快照比对 —— 主机改址后旧样本必须被拒收；</li>
     *   <li>行锁让**同一个目标**的写回跨 JVM 串行化。仅靠 {@code AtomicBoolean} 只能约束本进程，
     *       挡不住多实例部署下的交错。</li>
     * </ol>
     *
     * <p>锁顺序固定为：目标行 → 采集状态行 → SSH 配置行，全模块统一，避免死锁。
     *
     * @return 主机行；已被删除时返回 null（调用方据此拒收样本）
     */
    @Select("""
            SELECT id, hostname, ip, host_status
            FROM asset_host
            WHERE id = #{id}
            FOR UPDATE
            """)
    @Options(useCache = false)
    AssetHost selectForUpdate(@Param("id") Long id);

    /**
     * 采集成功时写入主机可达状态。**只改 host_status 与 updated_at**，
     * 绝不触碰 hostname / ip / 规格等人工资产字段，也绝不触碰 agent_status 与 last_heartbeat。
     *
     * @return 受影响行数（主机已被删除时为 0）
     */
    @Update("""
            UPDATE asset_host
            SET host_status = #{status}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateHostStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 失败达阈值 / 证据过期时把主机状态退回"无法确认"。
     *
     * <p>**条件更新**：只有当库里的值仍是采集上次写入的值时才改写。若期间已被别的来源改成别的值，
     * 说明我们不再是该值的所有者，此时不该把它抹掉。
     *
     * @return 受影响行数（0 表示值已被他方改写，本次不动）
     */
    @Update("""
            UPDATE asset_host
            SET host_status = #{newStatus}, updated_at = NOW()
            WHERE id = #{id} AND host_status = #{expectedStatus}
            """)
    int resetHostStatusIfObserved(@Param("id") Long id,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("newStatus") String newStatus);
}