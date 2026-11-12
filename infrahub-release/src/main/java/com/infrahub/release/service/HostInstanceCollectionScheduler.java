package com.infrahub.release.service;

import com.infrahub.release.config.HostInstanceCollectionProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主机与实例状态采集的调度器。
 *
 * <p><b>为什么不用 {@code @Scheduled}</b>：Spring 默认的调度线程池只有一个线程，已经被既有的
 * HTTP 采集任务（{@code infrahub.monitor.collection.fixed-delay-ms}）占用。本采集一轮要等待多批 SSH 探测，
 * 若共用该线程，会把既有采集任务一并拖住。因此这里用一个**专属单线程**调度器：
 * <ul>
 *   <li>单线程 + 固定延迟 ⇒ 从构造上不可能重入，也不会堆积；</li>
 *   <li>与既有调度互不影响；</li>
 *   <li>停机时可显式终止并做有界等待。</li>
 * </ul>
 *
 * <p><b>双重开关</b>：只有 {@code infrahub.monitor.collection.enabled} 与
 * {@code infrahub.monitor.collection.host-instance.enabled} 同时为 {@code true} 时默认启用。页面可临时覆盖当前进程的启停状态。
 * 默认两者都是 false，因此开发运行不会无意对真实主机发起探测。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 2.4 / 5.6 节
 */
@Slf4j
@Component

public class HostInstanceCollectionScheduler {

    private final HostInstanceCollectionService collectionService;
    private final HostInstanceCollectionProperties properties;
    private final HostInstanceCollectionControlService control;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    private volatile boolean stopped;

    public HostInstanceCollectionScheduler(HostInstanceCollectionService collectionService,
                                           HostInstanceCollectionProperties properties,
                                           HostInstanceCollectionControlService control) {
        this.collectionService = collectionService;
        this.properties = properties;
        this.control = control;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "host-instance-collection");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void start() {
        // 首个周期之后才首跑，避免应用刚启动就立刻发起一轮探测
        scheduler.scheduleWithFixedDelay(this::runOnce,
                properties.getPeriodMs(), properties.getPeriodMs(), TimeUnit.MILLISECONDS);
        log.info("主机/实例采集调度器就绪（每轮检查运行时开关）：period={}ms，单目标超时={}ms，并发={}，连续失败阈值={}，过期阈值={}ms",
                properties.getPeriodMs(), properties.getSingleTargetTimeoutMs(), properties.getMaxConcurrency(),
                properties.getFailureThreshold(), properties.getStaleThresholdMs());
    }

    /**
     * 单轮执行入口。
     *
     * <p>固定延迟 + 单线程已经保证不会重入，{@link AtomicBoolean} 只是二次防线
     * （例如日后有人手工触发同一入口时）。
     */
    void runOnce() {
        if (stopped || !control.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮采集尚未结束，跳过本轮，不堆积");
            return;
        }
        try {
            collectionService.collectOnce();
        } catch (Exception exception) {
            // 调度任务抛出异常会被调度器静默取消，这里必须兜住，本轮放弃、等下一轮
            log.error("状态采集本轮异常，等待下一轮", exception);
        } finally {
            running.set(false);
        }
    }

    /** 停机：终止后续调度并做有界等待。 */
    @PreDestroy
    public void shutdown() {
        stopped = true;
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(properties.getSingleTargetTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.warn("采集调度线程未在 {}ms 内结束，不再等待", properties.getSingleTargetTimeoutMs());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}