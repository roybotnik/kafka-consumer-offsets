package io.confluent.consumer.offsets.processor;

import kafka.coordinator.GroupTopicPartition;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.BytesDeserializer;
import org.apache.kafka.common.utils.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class OffsetsRestoreProcessor implements ConsumerOffsetsProcessor<GroupTopicPartition, Long> {

  private static final Logger LOG = LoggerFactory.getLogger(OffsetsRestoreProcessor.class);

  private final Properties properties;

  private final ThreadLocal<Map<String, KafkaConsumer<Bytes, Bytes>>> consumersCache
      = new ThreadLocal<Map<String, KafkaConsumer<Bytes, Bytes>>>() {
          @Override
          protected Map<String, KafkaConsumer<Bytes, Bytes>> initialValue() {
            return new HashMap<>();
          }
        };

  private final ThreadLocal<Map<GroupTopicPartition, Long>> maxOffsetsCache
      = new ThreadLocal<Map<GroupTopicPartition, Long>>() {
        @Override
        protected Map<GroupTopicPartition, Long> initialValue() {
          return new HashMap<>();
        }
      };

  public OffsetsRestoreProcessor(Properties properties) {
    this.properties = properties;
  }

  @Override
  public void process(GroupTopicPartition groupTopicPartition, Long offset) {
    String group = groupTopicPartition.group();
    KafkaConsumer<Bytes, Bytes> kafkaConsumer = this.consumersCache.get().get(group);
    if (kafkaConsumer == null) {
      kafkaConsumer = createKafkaConsumerForGroup(group);
      this.consumersCache.get().put(group, kafkaConsumer);
    }

    TopicPartition topicPartition = groupTopicPartition.topicPartition();
    if (!kafkaConsumer.assignment().contains(topicPartition)) {
      boolean topicPartitionExist = isTopicPartitionExist(topicPartition,
          kafkaConsumer.partitionsFor(topicPartition.topic()));
      if (topicPartitionExist) {
        List<TopicPartition> topicPartitions = Collections.singletonList(topicPartition);
        kafkaConsumer.assign(topicPartitions);
        Map<TopicPartition, Long> topicPartitionOffsets = kafkaConsumer.endOffsets(topicPartitions);
        long maxOffset = topicPartitionOffsets.get(topicPartition);
        this.maxOffsetsCache.get().put(groupTopicPartition, maxOffset);
      } else {
        LOG.warn("Non-existent topic/partition: {} - {}", topicPartition, offset);
        return;
      }
    }

    long maxOffset = this.maxOffsetsCache.get().get(groupTopicPartition);
    kafkaConsumer.seek(topicPartition, offset > maxOffset ? maxOffset : offset);
    kafkaConsumer.commitSync();
    LOG.debug("Offset was set: {} - {}", topicPartition, offset);
  }

  private boolean isTopicPartitionExist(TopicPartition topicPartition, List<PartitionInfo> partitionInfos) {
    for (PartitionInfo partitionInfo : partitionInfos) {
      if (partitionInfo.partition() == topicPartition.partition()) {
        return true;
      }
    }
    return false;
  }

  private KafkaConsumer<Bytes, Bytes> createKafkaConsumerForGroup(String group) {
    Properties properties = new Properties();
    properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        this.properties.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
    properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, group);
    properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE.toString());
    properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
        this.properties.getProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG));
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class.getName());
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, BytesDeserializer.class.getName());
    return new KafkaConsumer<>(properties);
  }

  @Override
  public void close() {
    Collection<KafkaConsumer<Bytes, Bytes>> kafkaConsumers = this.consumersCache.get().values();
    LOG.debug("Closing {} consumers", kafkaConsumers.size());
    for (KafkaConsumer<Bytes, Bytes> kafkaConsumer : kafkaConsumers) {
      try {
        kafkaConsumer.close();
      } catch (Exception e) {
        LOG.error("Error while closing", e);
      }
    }
  }
}
