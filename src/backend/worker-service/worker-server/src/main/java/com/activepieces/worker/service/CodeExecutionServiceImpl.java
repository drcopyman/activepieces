package com.activepieces.worker.service;

import com.activepieces.common.code.ArtifactMetadata;
import com.activepieces.common.error.exception.CodeArtifactBuildFailure;
import com.activepieces.entity.subdocuments.runs.ActionExecutionStatus;
import com.activepieces.worker.Sandbox;
import com.activepieces.worker.model.CodeExecutionStatusEnum;
import com.activepieces.worker.model.ExecutionCodeResult;
import com.activepieces.worker.workers.CodeBuildWorker;
import com.activepieces.worker.workers.CodeExecutionWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Service
@Log4j2
public class CodeExecutionServiceImpl implements CodeExecutionService {

  private static final int NUMBER_OF_WORKERS = 8;
  private static final int NUMBER_OF_TEST_WORKER = 4;

  private final ArrayList<CodeExecutionWorker> workers;
  private final BlockingQueue<Integer> blockingQueue;
  private final LocalArtifactCacheServiceImpl localArtifactCacheService;

  private final ArrayList<CodeExecutionWorker> testWorkers;
  private final BlockingQueue<Integer> testBlockingQueue;
  private final CodeBuildService codeBuildService;

  @Autowired
  public CodeExecutionServiceImpl(
      final LocalArtifactCacheServiceImpl localArtifactCacheService,
      final CodeBuildService codeBuildService,
      final ObjectMapper objectMapper) {
    this.codeBuildService = codeBuildService;
    this.localArtifactCacheService = localArtifactCacheService;
    this.blockingQueue = new LinkedBlockingQueue<>();
    this.workers = new ArrayList<>();
    for (int i = 0; i < NUMBER_OF_WORKERS; ++i) {
      this.blockingQueue.add(i);
      final Sandbox sandbox = new Sandbox(i);
      this.workers.add(new CodeExecutionWorker(objectMapper, sandbox));
    }

    this.testBlockingQueue = new LinkedBlockingQueue<>();
    this.testWorkers = new ArrayList<>();
    for (int i = 0; i < NUMBER_OF_TEST_WORKER; ++i) {
      this.testBlockingQueue.add(i);
      final Sandbox sandbox = new Sandbox(i + NUMBER_OF_WORKERS);
      this.testWorkers.add(new CodeExecutionWorker(objectMapper, sandbox));
    }
  }

  public ExecutionCodeResult executeCodeWithCache(
      JsonNode input, UUID resourceId, ArtifactMetadata artifactMetadata, String artifactFile)
      throws InterruptedException {
    try {
      int workerIndex = this.blockingQueue.take();
      CodeExecutionWorker codeExecutionWorker = this.workers.get(workerIndex);
      InputStream inputStream =
          localArtifactCacheService.cacheArtifact(resourceId, artifactMetadata, artifactFile);
      ExecutionCodeResult result = codeExecutionWorker.executeSync(input, inputStream);
      this.blockingQueue.add(workerIndex);
      return result;
    } catch (CodeArtifactBuildFailure e) {
      return ExecutionCodeResult.builder()
          .input(input)
          .verdict(CodeExecutionStatusEnum.INVALID_ARTIFACT)
          .status(ActionExecutionStatus.FAILED)
          .errorMessage(CodeExecutionStatusEnum.INVALID_ARTIFACT.getMessage())
          .build();
    }
  }

  public ExecutionCodeResult executeCode(JsonNode input, InputStream artifact) throws Exception {
    int workerIndex = this.testBlockingQueue.take();
    CodeExecutionWorker codeExecutionWorker = this.testWorkers.get(workerIndex);
    ExecutionCodeResult result;
    CodeBuildWorker codeBuildWorker = codeBuildService.obtainWorker();
    try {
      result =
              codeExecutionWorker.executeSync(input, codeBuildWorker.build(artifact));
    } finally {
      codeBuildService.releaseWorker(codeBuildWorker);
    }
    this.testBlockingQueue.add(workerIndex);
    return result;
  }
}
