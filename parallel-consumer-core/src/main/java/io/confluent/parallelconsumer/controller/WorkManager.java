package io.confluent.parallelconsumer.controller;

/*-
 * Copyright (C) 2020-2022 Confluent, Inc.
 */

import io.confluent.csid.utils.TimeUtils;
import io.confluent.parallelconsumer.ParallelConsumerOptions;
import io.confluent.parallelconsumer.internal.EpochAndRecordsMap;
import io.confluent.parallelconsumer.kafkabridge.BrokerPollSystem;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import pl.tlinkowski.unij.api.UniLists;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static java.lang.Boolean.TRUE;
import static lombok.AccessLevel.PUBLIC;

/**
 * Sharded, prioritised, offset managed, order controlled, delayed work queue.
 * <p>
 * Low Water Mark - the highest offset (continuously successful) with all it's previous messages succeeded (the offset
 * one commits to broker)
 * <p>
 * High Water Mark - the highest offset which has succeeded (previous may be incomplete)
 * <p>
 * Highest seen offset - the highest ever seen offset
 * <p>
 * This state is shared between the {@link BrokerPollSystem} thread and the {@link AbstractParallelEoSStreamProcessor}.
 *
 * @param <K>
 * @param <V>
 */
@Slf4j
public class WorkManager<K, V> implements ConsumerRebalanceListener {

    @Getter
    private final ParallelConsumerOptions<K, V> options;

    // todo make private
    @Getter(PUBLIC)
    final PartitionStateManager<K, V> pm;

    // todo make private
    @Getter(PUBLIC)
    private final ShardManager<K, V> sm;

    /**
     * The multiple of {@link ParallelConsumerOptions#getMaxConcurrency()} that should be pre-loaded awaiting
     * processing.
     * <p>
     * We use it here as well to make sure we have a matching number of messages in queues available.
     */
    private final DynamicLoadFactor dynamicLoadFactor;

    @Getter
    private int numberRecordsOutForProcessing = 0;

    /**
     * Useful for testing
     */
    @Getter(PUBLIC)
    private final List<Consumer<WorkContainer<K, V>>> successfulWorkListeners = new ArrayList<>();

    public WorkManager(ParallelConsumerOptions<K, V> options, org.apache.kafka.clients.consumer.Consumer<K, V> consumer) {
        this(options, consumer, new DynamicLoadFactor(), TimeUtils.getClock());
    }

    /**
     * Use a private {@link DynamicLoadFactor}, useful for testing.
     */
    public WorkManager(ParallelConsumerOptions<K, V> options, org.apache.kafka.clients.consumer.Consumer<K, V> consumer, Clock clock) {
        this(options, consumer, new DynamicLoadFactor(), clock);
    }

    public WorkManager(final ParallelConsumerOptions<K, V> newOptions, final org.apache.kafka.clients.consumer.Consumer<K, V> consumer,
                       final DynamicLoadFactor dynamicExtraLoadFactor, Clock clock) {
        this.options = newOptions;
        this.dynamicLoadFactor = dynamicExtraLoadFactor;
        this.sm = new ShardManager<>(options, this, clock);
        this.pm = new PartitionStateManager<>(consumer, sm, options, clock);
    }

    /**
     * Load offset map for assigned partitions
     */
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        pm.onPartitionsAssigned(partitions);
    }

    /**
     * Clear offset map for revoked partitions
     * <p>
     * {@link AbstractParallelEoSStreamProcessor#onPartitionsRevoked} handles committing off offsets upon revoke
     *
     * @see AbstractParallelEoSStreamProcessor#onPartitionsRevoked
     */
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        pm.onPartitionsRevoked(partitions);
    }

    public void registerWork(EpochAndRecordsMap<K, V> records) {
        pm.maybeRegisterNewRecordAsWork(records);
    }

    /**
     * Get work with no limit on quantity, useful for testing.
     */
    public List<WorkContainer<K, V>> getWorkIfAvailable() {
        return getWorkIfAvailable(Integer.MAX_VALUE);
    }

    /**
     * Depth first work retrieval.
     */
    public List<WorkContainer<K, V>> getWorkIfAvailable(final int requestedMaxWorkToRetrieve) {
        // optimise early
        if (requestedMaxWorkToRetrieve < 1) {
            return UniLists.of();
        }

        //
        var work = sm.getWorkIfAvailable(requestedMaxWorkToRetrieve);

        //
        log.debug("Got {} of {} requested records of work. In-flight: {}, Awaiting in commit (partition) queues: {}",
                work.size(),
                requestedMaxWorkToRetrieve,
                getNumberRecordsOutForProcessing(),
                getNumberOfEntriesInPartitionQueues());
        numberRecordsOutForProcessing += work.size();

        return work;
    }

    public void onSuccessResult(WorkContainer<K, V> wc) {
        log.trace("Work success ({}), removing from processing shard queue", wc);

        wc.endFlight();

        // update as we go
        pm.onSuccess(wc);
        sm.onSuccess(wc);

        // notify listeners
        successfulWorkListeners.forEach(c -> c.accept(wc));

        numberRecordsOutForProcessing--;
    }

    /**
     * Can run from controller or poller thread, depending on which is responsible for committing
     *
     * @see PartitionStateManager#onOffsetCommitSuccess(Map)
     */
    public void onOffsetCommitSuccess(Map<TopicPartition, OffsetAndMetadata> committed) {
        pm.onOffsetCommitSuccess(committed);
    }

    public void onFailureResult(WorkContainer<K, V> wc) {
        // error occurred, put it back in the queue if it can be retried
        wc.endFlight();
        pm.onFailure(wc);
        sm.onFailure(wc);
        numberRecordsOutForProcessing--;
    }

    public long getNumberOfEntriesInPartitionQueues() {
        return pm.getNumberOfEntriesInPartitionQueues();
    }

    public Map<TopicPartition, OffsetAndMetadata> collectCommitDataForDirtyPartitions() {
        return pm.collectDirtyCommitData();
    }

    /**
     * Have our partitions been revoked? Can a batch contain messages of different epochs?
     *
     * @return true if any epoch is stale, false if not
     * @see #checkIfWorkIsStale(WorkContainer)
     */
    public boolean checkIfWorkIsStale(final List<WorkContainer<K, V>> workContainers) {
        for (final WorkContainer<K, V> workContainer : workContainers) {
            if (checkIfWorkIsStale(workContainer)) return true;
        }
        return false;
    }

    /**
     * Have our partitions been revoked?
     *
     * @return true if epoch doesn't match, false if ok
     */
    public boolean checkIfWorkIsStale(final WorkContainer<K, V> workContainer) {
        return pm.checkIfWorkIsStale(workContainer);
    }

    public boolean shouldThrottle() {
        return isSufficientlyLoaded();
    }

    /**
     * @return true if there's enough messages downloaded from the broker already to satisfy the pipeline, false if more
     * should be downloaded (or pipelined in the Consumer)
     */
    public boolean isSufficientlyLoaded() {
        return getNumberOfWorkQueuedInShardsAwaitingSelection() > (long) options.getTargetAmountOfRecordsInFlight() * getLoadingFactor();
    }

    private int getLoadingFactor() {
        return dynamicLoadFactor.getCurrentFactor();
    }

    public boolean workIsWaitingToBeProcessed() {
        return sm.workIsWaitingToBeProcessed();
    }

    public boolean hasWorkInFlight() {
        return getNumberRecordsOutForProcessing() != 0;
    }

    public boolean isWorkInFlightMeetingTarget() {
        return getNumberRecordsOutForProcessing() >= options.getTargetAmountOfRecordsInFlight();
    }

    public long getNumberOfWorkQueuedInShardsAwaitingSelection() {
        return sm.getNumberOfWorkQueuedInShardsAwaitingSelection();
    }

    public boolean hasWorkInCommitQueues() {
        return pm.hasWorkInCommitQueues();
    }

    public boolean isRecordsAwaitingProcessing() {
        return sm.getNumberOfWorkQueuedInShardsAwaitingSelection() > 0;
    }

    public boolean isRecordsAwaitingToBeCommitted() {
        // todo could be improved - shouldn't need to count all entries if we simply want to know if there's > 0
        var partitionWorkRemainingCount = getNumberOfEntriesInPartitionQueues();
        return partitionWorkRemainingCount > 0;
    }

    public void handleFutureResult(WorkContainer<K, V> wc) {
        if (checkIfWorkIsStale(wc)) {
            // no op, partition has been revoked
            log.debug("Work result received, but from an old generation. Dropping work from revoked partition {}", wc);
        } else {
            Optional<Boolean> userFunctionSucceeded = wc.getMaybeUserFunctionSucceeded();
            if (userFunctionSucceeded.isPresent()) {
                if (TRUE.equals(userFunctionSucceeded.get())) {
                    onSuccessResult(wc);
                } else {
                    onFailureResult(wc);
                }
            } else {
                throw new IllegalStateException("Work returned, but without a success flag - report a bug");
            }
        }
    }

    public boolean isNoRecordsOutForProcessing() {
        return getNumberRecordsOutForProcessing() == 0;
    }

    public Optional<Duration> getLowestRetryTime() {
        return sm.getLowestRetryTime();
    }

}