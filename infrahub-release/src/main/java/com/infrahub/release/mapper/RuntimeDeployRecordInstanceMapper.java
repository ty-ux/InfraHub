package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.RuntimeDeployRecordInstance;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuntimeDeployRecordInstanceMapper extends BaseMapper<RuntimeDeployRecordInstance> {

    /**
     * 表是否存在探针。用 {@code to_regclass} 而不是直接 SELECT：表不存在时后者会报错，
     * 在 PostgreSQL 里会把当前事务标记为 aborted，连后续写入都无法继续。
     */
    @Select("SELECT to_regclass('runtime_deploy_record_instance')::text")
    String probe();

    /**
     * 写入“这条部署记录部署的是哪个实例”。与记录插入在同一个短事务内执行。
     *
     * <p>{@code ON CONFLICT (deploy_record_id) DO NOTHING}：一条记录只允许一个实例归属，
     * 重复调用返回 0，调用方据此拒绝继续（刚插入的行不可能已有归属，返回 0 说明归属存疑）。
     *
     * @return 1 表示写入生效；0 表示该记录已经有归属行
     */
    @Insert("""
            INSERT INTO runtime_deploy_record_instance(
                deploy_record_id, instance_id, service_id, env_id, host_id,
                instance_name, instance_type, created_at
            ) VALUES (
                #{deployRecordId}, #{instanceId}, #{serviceId}, #{envId}, #{hostId},
                #{instanceName}, #{instanceType}, NOW()
            )
            ON CONFLICT (deploy_record_id) DO NOTHING
            """)
    int insertIfAbsent(RuntimeDeployRecordInstance row);

    /** 单条部署记录的实例归属；没有关联行时返回 null。 */
    @Select("""
            SELECT *
            FROM runtime_deploy_record_instance
            WHERE deploy_record_id = #{deployRecordId}
            """)
    RuntimeDeployRecordInstance selectByDeployRecordId(@Param("deployRecordId") Long deployRecordId);

    /**
     * 一批部署记录的实例归属。
     *
     * <p>调用方必须保证 {@code ids} 非空：{@code IN ()} 在 PostgreSQL 上是语法错误，
     * 而这里不做“空集合退化成全表”的兜底。
     */
    @Select("""
            <script>
            SELECT *
            FROM runtime_deploy_record_instance
            WHERE deploy_record_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY deploy_record_id
            </script>
            """)
    List<RuntimeDeployRecordInstance> listByDeployRecordIds(@Param("ids") List<Long> ids);

    /** 某实例的部署归属历史（升序），用于只读核对。 */
    @Select("""
            SELECT *
            FROM runtime_deploy_record_instance
            WHERE instance_id = #{instanceId}
            ORDER BY id
            """)
    List<RuntimeDeployRecordInstance> listByInstanceId(@Param("instanceId") Long instanceId);
}
