package com.infrahub.pipeline.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PipelineRunRecoveryListener {

    private final PipelineRunService pipelineRunService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        pipelineRunService.recoverInterruptedRuns();
    }
}
