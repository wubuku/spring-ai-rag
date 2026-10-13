package com.springairag.core.documentprocessing;

import com.springairag.core.config.RagDocumentProcessingProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界收敛处理账本与旧 Job 状态的后台任务。 */
@Component
public class DocumentProcessingHousekeeping {

    private final DocumentProcessingRunRepository runRepository;
    private final EmbeddingJobRepository jobRepository;
    private final RagDocumentProcessingProperties properties;

    public DocumentProcessingHousekeeping(
            DocumentProcessingRunRepository runRepository,
            EmbeddingJobRepository jobRepository,
            RagProperties ragProperties) {
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.properties = ragProperties.getDocumentProcessing();
    }

    @Scheduled(fixedDelayString =
            "${rag.document-processing.housekeeping-interval-ms:60000}")
    public void scan() {
        if (!properties.isEnabled() || !properties.isHousekeepingEnabled()) {
            return;
        }
        runRepository.activeForHousekeeping(
                        properties.getMaxHousekeepingRunsPerScan())
                .forEach(run -> {
                    if (run.embeddingJobId() != null) {
                        java.util.Optional<EmbeddingJob> job =
                                jobRepository.find(run.embeddingJobId());
                        if (job.isPresent()) {
                            runRepository.mirrorJob(job.get());
                        } else {
                            runRepository.markStuck(
                                    run.id(), run.requestGeneration(),
                                    properties.getStuckThresholdSeconds());
                        }
                    } else {
                        runRepository.markStuck(
                                run.id(), run.requestGeneration(),
                                properties.getStuckThresholdSeconds());
                    }
                });
    }
}
