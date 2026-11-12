package com.infrahub.release.service;

import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.support.CollectionSwitches;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 当前后端进程的运行时覆盖；重启恢复部署配置，不改变 HTTP 采集开关。 */
@Service
public class HostInstanceCollectionControlService {
    private final HostInstanceCollectionProperties properties;
    private final boolean globalEnabled;
    private volatile Boolean override;

    public HostInstanceCollectionControlService(HostInstanceCollectionProperties properties,
            @Value("${" + CollectionSwitches.GLOBAL_ENABLED + ":false}") boolean globalEnabled) {
        this.properties = properties;
        this.globalEnabled = globalEnabled;
    }

    public boolean isEnabled() {
        Boolean current = override;
        return current != null ? current : globalEnabled && properties.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        override = enabled;
    }

    public ScheduleState state() {
        return new ScheduleState(isEnabled(), properties.getPeriodMs(), "CURRENT_PROCESS", true);
    }

    public record ScheduleState(boolean enabled, long periodMs, String scope, boolean resetsOnRestart) {}
}
