package io.confluent.consumer.offsets.blacklist;

import kafka.common.OffsetAndMetadata;
import kafka.common.OffsetMetadata;
import kafka.coordinator.GroupTopicPartition;
import org.apache.kafka.common.TopicPartition;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class GroupRegexpBlacklistTest {

  private GroupRegexpBlacklist blacklist;

  @Before
  public void setUp() throws Exception {
    this.blacklist = new GroupRegexpBlacklist("a*");
  }

  @Test
  public void shouldIgnore() throws Exception {
    TopicPartition aTopicPartition = new TopicPartition("aaa", 0);
    GroupTopicPartition aGroupTopicPartition = new GroupTopicPartition("aaa", aTopicPartition);
    GroupTopicPartition bGroupTopicPartition = new GroupTopicPartition("bbb", aTopicPartition);
    OffsetMetadata offsetMetadata = new OffsetMetadata(0L, OffsetMetadata.NoMetadata());
    assertTrue(this.blacklist.shouldIgnore(aGroupTopicPartition, new OffsetAndMetadata(offsetMetadata, 0L, 0L)));
    assertFalse(this.blacklist.shouldIgnore(bGroupTopicPartition, new OffsetAndMetadata(offsetMetadata, 0L, 0L)));
  }
}