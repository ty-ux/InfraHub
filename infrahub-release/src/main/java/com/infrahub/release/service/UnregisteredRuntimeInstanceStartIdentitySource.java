package com.infrahub.release.service;

import com.infrahub.release.support.RuntimeInstanceStartIdentity;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 当前唯一生效的身份读取实现：**恒返回空**，因为仓库里还没有任何启动身份存储。
 *
 * <p><b>为什么是空而不是"最佳猜测"</b>：{@code runtime_service_instance} 没有启动代次、boot_id、
 * 进程起始时间或制品摘要列，部署命令又是不可知的用户模板；任何从 PID 文件、端口监听者或命令行
 * 反推出来的身份都只是弱关联。宁可让"身份不可得"成为明确结论，也不把猜测包装成身份。
 *
 * <p><b>要变成真实实现需要同时具备</b>（缺一不可，均不在本任务授权范围内）：
 * <ol>
 *   <li>数据库迁移：身份表/列（实例、主机、代次、boot_id、pid、起始时间、制品摘要、部署记录、状态）；</li>
 *   <li>部署侧写入方：由平台提供的启动包装器在进程启动成功后原子写入身份并登记，而不是解析用户命令输出；</li>
 *   <li>目标机独立执行者：在本地复核身份并用 pidfd 发信号、回传带签名的结果（见设计文档）。</li>
 * </ol>
 *
 * <p>在上述三项就绪之前，本类保持"无身份"语义：停止请求只会得到
 * {@code IDENTITY_UNCONFIRMED}，不会发送任何信号。
 */
@Service
public class UnregisteredRuntimeInstanceStartIdentitySource implements RuntimeInstanceStartIdentitySource {

    @Override
    public Optional<RuntimeInstanceStartIdentity> findActive(Long instanceId) {
        return Optional.empty();
    }
}
