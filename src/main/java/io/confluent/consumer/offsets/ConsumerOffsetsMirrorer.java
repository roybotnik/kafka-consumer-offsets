package io.confluent.consumer.offsets;

import io.confluent.consumer.offsets.blacklist.CompositeBlacklist;
import io.confluent.consumer.offsets.blacklist.ConsumerOffsetsBlacklist;
import io.confluent.consumer.offsets.blacklist.GroupRegexpBlacklist;
import io.confluent.consumer.offsets.blacklist.TopicRegexpBlacklist;
import io.confluent.consumer.offsets.converter.ConsumerOffsetsConverter;
import io.confluent.consumer.offsets.converter.MirrorerConverter;
import io.confluent.consumer.offsets.function.IdentityFunction;
import io.confluent.consumer.offsets.processor.ConsistentHashingAsyncProcessor;
import io.confluent.consumer.offsets.processor.ConsumerOffsetsProcessor;
import io.confluent.consumer.offsets.processor.LoggingProcessor;
import io.confluent.consumer.offsets.processor.CompositeProcessor;
import io.confluent.consumer.offsets.processor.OffsetsSinkProcessor;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import kafka.common.OffsetAndMetadata;
import kafka.coordinator.GroupTopicPartition;
import org.apache.kafka.common.utils.Bytes;

import java.io.FileReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.Properties;

import static java.lang.String.format;
import static java.lang.System.exit;

public class ConsumerOffsetsMirrorer {

  public static void main(String[] args) throws Exception {
    OptionParser parser = new OptionParser();
    OptionSpec<String> consumerConfig = parser.accepts("consumer.config",
        "Embedded consumer config for reading from the source cluster.")
        .withRequiredArg()
        .ofType(String.class);
    OptionSpec<String> producerConfig = parser.accepts("producer.config",
        "Embedded producer config for writing events on target cluster.")
        .withRequiredArg()
        .ofType(String.class);
    OptionSpec<String> sourceTopic = parser.accepts("source.topic",
        "Source topic to read consumer offsets.")
        .withRequiredArg()
        .ofType(String.class)
        .defaultsTo("__consumer_offsets");
    OptionSpec<String> targetTopic = parser.accepts("target.topic",
        "Target topic to write consumer offsets.")
        .withRequiredArg()
        .ofType(String.class)
        .defaultsTo("replica_consumer_offsets");
    OptionSpec<Integer> numberOfThreads = parser.accepts("num.threads",
        "Number of production threads.")
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(10);
    OptionSpec<Integer> pollTimeoutMs = parser.accepts("poll-timeout-ms",
        "Poll timeout for source topic.")
        .withRequiredArg()
        .ofType(Integer.class)
        .defaultsTo(Integer.MAX_VALUE);
    OptionSpec fromBeginning = parser.accepts("from-beginning",
        "Start consumption from the beginning of a topic.");
    OptionSpec help = parser.accepts("help", "Print this message.");

    OptionSet options = parser.parse(args);

    if (args.length == 0 || options.has(help)) {
      parser.printHelpOn(System.out);
      exit(0);
    }

    for (OptionSpec<String> requiredOption : Arrays.asList(consumerConfig, producerConfig)) {
      if (!options.has(requiredOption)) {
        System.out.println(format("Missing required argument: %s", requiredOption));
        parser.printHelpOn(System.out);
        exit(0);
      }
    }

    Properties consumerProperties = new Properties();
    try (Reader reader = new FileReader(options.valueOf(consumerConfig))) {
      consumerProperties.load(reader);
    }

    Properties producerProperties = new Properties();
    try (Reader reader = new FileReader(options.valueOf(producerConfig))) {
      producerProperties.load(reader);
    }

    ConsumerOffsetsProcessor<GroupTopicPartition, OffsetAndMetadata> offsetsProcessor
        = new CompositeProcessor.Builder<GroupTopicPartition, OffsetAndMetadata>()
            .process(new LoggingProcessor<GroupTopicPartition, OffsetAndMetadata>())
            .process(new ConsistentHashingAsyncProcessor<>(options.valueOf(numberOfThreads),
                new IdentityFunction<GroupTopicPartition>(),
                  new OffsetsSinkProcessor(producerProperties, options.valueOf(targetTopic))))
            .build();

    ConsumerOffsetsBlacklist<GroupTopicPartition, OffsetAndMetadata> offsetsBlacklist
        = new CompositeBlacklist.Builder<GroupTopicPartition, OffsetAndMetadata>()
          .ignore(new GroupRegexpBlacklist("kafka-consumers-offsets.*"))
          .ignore(new GroupRegexpBlacklist("kafka-consumer-offsets.*"))
          .ignore(new TopicRegexpBlacklist("console-consumer.*"))
          .ignore(new TopicRegexpBlacklist("_.*"))
          .build();

    ConsumerOffsetsConverter<Bytes, Bytes, GroupTopicPartition, OffsetAndMetadata> offsetsConverter =
        new MirrorerConverter();

    final ConsumerOffsetsLoop<Bytes, Bytes, GroupTopicPartition, OffsetAndMetadata> consumerOffsetsLoop =
        new ConsumerOffsetsLoop<>(consumerProperties, offsetsProcessor, offsetsBlacklist, offsetsConverter,
            options.valueOf(sourceTopic), options.has(fromBeginning), options.valueOf(pollTimeoutMs), false);

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        consumerOffsetsLoop.stop();
      }
    });

    Thread thread = new Thread(consumerOffsetsLoop);
    thread.start();
    thread.join();
  }
}