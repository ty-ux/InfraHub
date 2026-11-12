package com.infrahub.release.service;

import com.infrahub.pipeline.event.PipelineCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ReleasePipelineCompletionListener {

    private final ReleasePipelineService releasePipelineService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPipelineCompleted(PipelineCompletedEvent event) {
        releasePipelineService.handlePipelineCompleted(event);
    }
}
