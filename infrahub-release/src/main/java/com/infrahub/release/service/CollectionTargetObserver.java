package com.infrahub.release.service;

import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionTargetRef;

/**
 * 采集观测器：**只读探测，绝不写库**。写回由 {@link HostInstanceCollectionService} 统一负责。
 *
 * <p>抽成接口是为了让编排层（并发、超时、乱序、写回条件）可以用一个可控的假观测器独立测试，
 * 而不必连真实 SSH。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 2.3 / 7.1 节
 */
public interface CollectionTargetObserver {

    /** 观测器标识，写入 monitor_collection_state.observer_type */
    String observerType();

    /**
     * 该观测器是否支持这个目标。
     *
     * <p>不支持的目标必须由编排层显式判为 SKIPPED_UNSUPPORTED，**不得**回退到别的探测方式。
     */
    boolean supports(CollectionTargetRef target);

    /** 执行一次只读探测。实现必须自行把异常归类为 {@link CollectionObservation}，不得外抛。 */
    CollectionObservation observe(CollectionTargetRef target);
}