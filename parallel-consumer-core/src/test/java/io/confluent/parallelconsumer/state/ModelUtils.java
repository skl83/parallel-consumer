package io.confluent.parallelconsumer.state;

/*-
 * Copyright (C) 2020-2022 Confluent, Inc.
 */

import io.confluent.csid.utils.TimeUtils;
import io.confluent.parallelconsumer.internal.EpochAndRecordsMap;
import io.confluent.parallelconsumer.internal.PCModuleTestEnv;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import io.confluent.parallelconsumer.internal.PCModuleTestEnv;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.mockito.Mockito;
import pl.tlinkowski.unij.api.UniLists;
import pl.tlinkowski.unij.api.UniMaps;

import java.util.List;

@RequiredArgsConstructor
//@RequiredArgsConstructor
public class ModelUtils {

    private final PCModuleTestEnv module;

    public WorkContainer<String, String> createWorkFor(long offset) {
        //noinspection unchecked
        ConsumerRecord<String, String> mockCr = Mockito.mock(ConsumerRecord.class);
        WorkContainer<String, String> workContainer = new WorkContainer<>(0, mockCr, null, TimeUtils.getClock());
        Mockito.doReturn(offset).when(mockCr).offset();
        return workContainer;
    }

    public EpochAndRecordsMap<String, String> createFreshWork() {
        return new EpochAndRecordsMap<>(createConsumerRecords(), module.workManager().getPm());
    }

    public ConsumerRecords<String, String> createConsumerRecords() {
        return new ConsumerRecords<>(UniMaps.of(getPartition(), UniLists.of(
                createConsumerRecord(topic)
        )));
    }

    @Getter
    final String topic = "topic";

    @NonNull
    public TopicPartition getPartition() {
        return new TopicPartition(topic, 0);
    }

    @NonNull
    public List<TopicPartition> getPartitions() {
        return UniLists.of(new TopicPartition(topic, 0));
    }

    long nextOffset = 0L;

    @NonNull
    private ConsumerRecord<String, String> createConsumerRecord(String topic) {
        var cr = new ConsumerRecord<>(topic, 0, nextOffset, "a-key", "a-value");
        nextOffset++;
        return cr;
    }

    // todo duplicate?
    public ProducerRecord<String, String> createProducerRecords() {
        return new ProducerRecord<>(topic, "a-key", "a-value");
    }

    final String groupId = "cg-1";

    public ConsumerGroupMetadata consumerGroupMeta() {
        return new ConsumerGroupMetadata(groupId);
    }
}
