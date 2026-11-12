package com.infrahub.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.base.entity.BaseServiceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BaseServiceMapper extends BaseMapper<BaseServiceEntity> {

    /**
     * 服务行锁：对 base_service 行加排他行锁（SELECT ... FOR UPDATE）。
     *
     * <p>三个执行入口共用这一把锁，用来把"同一服务在同一环境下的活动操作"串行化：
     * <ul>
     *   <li>发布执行认领（{@code ReleaseExecutionClaimService}）：认领检查与条件更新 RUNNING 同事务；</li>
     *   <li>回滚执行认领（{@code RollbackTargetCoordinator#lockForExecution}）：先锁服务行，再锁回滚单；</li>
     *   <li>移除运行实例登记（{@code RuntimeInstanceDeregistrationService}）：先锁服务行，再锁实例行，
     *       然后检查是否存在执行中的发布/回滚。</li>
     * </ul>
     * 锁粒度限定到单个服务行，不同服务之间互不阻塞，也不存在跨请求的全局锁。
     *
     * <p>必须在真实生效的事务内调用：行锁随事务提交或回滚释放。若当前没有活动事务，
     * 语句走自动提交并立即释放锁，起不到协调作用，因此调用方都会先做事务守卫。
     *
     * <p>返回 1 表示锁定成功；返回 null 表示服务行不存在，由调用方按"服务不存在"处理。
     */
    @Select("SELECT id FROM base_service WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}