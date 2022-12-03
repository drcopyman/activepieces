package com.activepieces.flow;

import com.activepieces.common.AggregateKey;
import com.activepieces.common.PaginationService;
import com.activepieces.common.SeekPage;
import com.activepieces.common.SeekPageRequest;
import com.activepieces.common.error.ErrorServiceHandler;
import com.activepieces.common.error.exception.flow.FlowNotFoundException;
import com.activepieces.common.error.exception.flow.FlowVersionAlreadyLockedException;
import com.activepieces.common.error.exception.flow.FlowVersionNotFoundException;
import com.activepieces.entity.enums.*;
import com.activepieces.entity.nosql.Flow;
import com.activepieces.flow.mapper.FlowMapper;
import com.activepieces.flow.model.CreateFlowRequest;
import com.activepieces.flow.model.FlowVersionView;
import com.activepieces.flow.model.FlowView;
import com.activepieces.flow.repository.FlowRepository;
import com.activepieces.guardian.client.PermissionService;
import com.activepieces.guardian.client.exception.PermissionDeniedException;
import com.activepieces.guardian.client.exception.ResourceNotFoundException;
import com.activepieces.lockservice.CannotAcquireLockException;
import com.activepieces.lockservice.LockNameService;
import com.activepieces.lockservice.LockService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;


@Log4j2
@Service
public class FlowServiceImpl implements FlowService {

  private final FlowRepository repository;
  private final FlowVersionServiceImpl flowVersionService;
  private final FlowMapper flowMapper;
  private final PermissionService permissionService;
  private final PaginationService paginationService;
  private final ErrorServiceHandler errorServiceHandler;
  private final LockNameService lockNameService;
  private final LockService lockService;
  private final Duration LOCK_TIMEOUT = Duration.ofMinutes(2);
  private final Duration LOCK_EXPIRY = Duration.ofMinutes(1);

  @Autowired
  public FlowServiceImpl(
      final FlowRepository repository,
      final LockNameService lockNameService,
      final LockService lockService,
      final ErrorServiceHandler errorServiceHandler,
      final FlowVersionServiceImpl flowVersionService,
      final FlowMapper flowMapper,
      final PaginationService paginationService,
      final PermissionService permissionService) {
    this.repository = repository;
    this.lockService = lockService;
    this.lockNameService = lockNameService;
    this.errorServiceHandler = errorServiceHandler;
    this.paginationService = paginationService;
    this.flowVersionService = flowVersionService;
    this.flowMapper = flowMapper;
    this.permissionService = permissionService;
  }

  @Override
  public SeekPage<FlowView> listByCollectionId(UUID integrationId, SeekPageRequest pageable)
      throws FlowNotFoundException, PermissionDeniedException {
    permissionService.requiresPermission(integrationId, Permission.READ_FLOW);
    Flow startingAfter =
        Objects.nonNull(pageable.getStartingAfter())
            ? flowMapper.fromView(get(pageable.getStartingAfter()))
            : null;
    Flow endingBefore =
        Objects.nonNull(pageable.getEndingBefore())
            ? flowMapper.fromView(get(pageable.getEndingBefore()))
            : null;
    final AggregateKey aggregateKey =
        AggregateKey.builder().aggregateKey(Flow.COLLECTION_ID).value(integrationId).build();
    SeekPage<Flow> seekPage =
        paginationService.paginationTimeAsc(
            aggregateKey,
            startingAfter,
            endingBefore,
            pageable.getLimit(),
            Flow.class,
            Collections.emptyList());
    return seekPage.convert(flowMapper::toView);
  }

  @Override
  public int countByCollectionId(UUID collectionId) throws PermissionDeniedException {
    permissionService.requiresPermission(collectionId, Permission.READ_FLOW);
    final AggregateKey aggregateKey =
        AggregateKey.builder().aggregateKey(Flow.COLLECTION_ID).value(collectionId).build();
    return (int)
        paginationService.count(
            aggregateKey,
            Collections.emptyList(),
            Flow.class);
  }

  @Override
  public FlowView create(UUID projectId, UUID collectionId, CreateFlowRequest createRequest)
      throws PermissionDeniedException, ResourceNotFoundException{
    permissionService.requiresPermission(collectionId, Permission.WRITE_FLOW);
    UUID flowId = UUID.randomUUID();

    FlowView flowView =
        FlowView.builder()
            .versionsList(Collections.emptyList())
            .id(flowId)
            .collectionId(collectionId)
            .epochCreationTime(Instant.now().toEpochMilli())
            .epochUpdateTime(Instant.now().toEpochMilli())
            .build();
    FlowView savedFlowView = saveFromView(flowView);
    permissionService.createResourceWithParent(flowId, collectionId, ResourceType.FLOW);

    // We need to save the flow then the version in order to attach it to the parent as resource
    FlowVersionView versionView =
        flowVersionService.createNew(flowId, null, createRequest.getVersion());
    return saveFromView(
        savedFlowView.toBuilder()
            .versionsList(Collections.singletonList(versionView.getId()))
            .build());
  }

  @Override
  public Optional<FlowView> getOptional(UUID id) throws PermissionDeniedException {
    Optional<Flow> flowMetadataOptional = repository.findById(id);
    if (flowMetadataOptional.isEmpty()) {
      return Optional.empty();
    }
    permissionService.requiresPermission(id, Permission.READ_FLOW);
    return flowMetadataOptional.map(flowMapper::toView);
  }

  @Override
  public FlowView get(UUID id) throws FlowNotFoundException, PermissionDeniedException {
    Optional<FlowView> flowMetadataOptional = getOptional(id);
    if (flowMetadataOptional.isEmpty()) {
      throw new FlowNotFoundException(id);
    }
    return flowMetadataOptional.get();
  }

  @Override
  public FlowView updateDraft(UUID flowId, FlowVersionView request, long updateTimestamp)
      throws FlowNotFoundException, FlowVersionNotFoundException, PermissionDeniedException, ResourceNotFoundException,
          FlowVersionAlreadyLockedException {
    permissionService.requiresPermission(flowId, Permission.WRITE_FLOW);
    try {
      final String lockName = lockNameService.saveFlowLock(flowId);
      final String token = lockService.waitUntilAcquire(lockName, LOCK_EXPIRY, LOCK_TIMEOUT);

      FlowView flow = get(flowId);
      if (flow.getEpochUpdateTime() > updateTimestamp) {
        log.info(
            "Ignoring update on flow {}, timestamp {} is older than {}",
            flowId,
            updateTimestamp,
            flow.getEpochUpdateTime());
        lockService.release(lockName, token);
        return flow;
      }

      FlowVersionView draft = flow.getLastVersion();
      if (flow.getLastVersion().getState().equals(EditState.LOCKED)) {
        draft = cloneVersion(flowId, draft).getLastVersion();
      }
      FlowVersionView updatedVersion = flowVersionService.update(draft.getId(), request);
      flow.updateOrCreateDraft(updatedVersion);
      FlowView savedVersion = saveFromView(flow);

      lockService.release(lockName, token);
      return savedVersion;
    } catch (CannotAcquireLockException exception) {
      throw errorServiceHandler.createInternalError(exception);
    }
  }

  private FlowView cloneVersion(UUID flowId, FlowVersionView draftVersion)
      throws FlowNotFoundException, PermissionDeniedException,
          ResourceNotFoundException {
    FlowView flow = get(flowId);
    FlowVersionView clonedVersion =
        flowVersionService.createNew(flowId, draftVersion.getId(), draftVersion);
    flow.updateOrCreateDraft(clonedVersion);
    return saveFromView(flow);
  }

  private FlowView saveFromView(FlowView flow) {
    return flowMapper.toView(repository.save(flowMapper.fromView(flow)));
  }

  // TODO HARD DELETE
  @Override
  public void archive(UUID id) throws FlowNotFoundException, PermissionDeniedException {
    permissionService.requiresPermission(id, Permission.WRITE_FLOW);
  //  FlowView flow = get(id).toBuilder().status(EntityStatus.ARCHIVED).build();
   // saveFromView(flow);
  }
}
