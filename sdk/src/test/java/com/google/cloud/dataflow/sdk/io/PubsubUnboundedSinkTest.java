/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.io;

import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.io.PubsubUnboundedSink.RecordIdMethod;
import com.google.cloud.dataflow.sdk.testing.CoderProperties;
import com.google.cloud.dataflow.sdk.testing.TestPipeline;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.util.PubsubClient;
import com.google.cloud.dataflow.sdk.util.PubsubClient.OutgoingMessage;
import com.google.cloud.dataflow.sdk.util.PubsubClient.TopicPath;
import com.google.cloud.dataflow.sdk.util.PubsubTestClient;
import com.google.cloud.dataflow.sdk.util.PubsubTestClient.PubsubTestClientFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Test PubsubUnboundedSink.
 */
@RunWith(JUnit4.class)
public class PubsubUnboundedSinkTest {
  private static final TopicPath TOPIC = PubsubClient.topicPathFromName("testProject", "testTopic");
  private static final String DATA = "testData";
  private static final long TIMESTAMP = 1234L;
  private static final String TIMESTAMP_LABEL = "timestamp";
  private static final String ID_LABEL = "id";
  private static final int NUM_SHARDS = 10;

  private static class Stamp extends DoFn<String, String> {
    @Override
    public void processElement(ProcessContext c) {
      c.outputWithTimestamp(c.element(), new Instant(TIMESTAMP));
    }
  }

  private String getRecordId(String data) {
    return Hashing.murmur3_128().hashBytes(data.getBytes()).toString();
  }

  @Test
  public void saneCoder() throws Exception {
    OutgoingMessage message = new OutgoingMessage(DATA.getBytes(), TIMESTAMP, getRecordId(DATA));
    CoderProperties.coderDecodeEncodeEqual(PubsubUnboundedSink.CODER, message);
    CoderProperties.coderSerializable(PubsubUnboundedSink.CODER);
  }

  @Test
  public void sendOneMessage() throws IOException {
    List<OutgoingMessage> outgoing =
        ImmutableList.of(new OutgoingMessage(DATA.getBytes(), TIMESTAMP, getRecordId(DATA)));
    int batchSize = 1;
    int batchBytes = 1;
    try (PubsubTestClientFactory factory =
             PubsubTestClient.createFactoryForPublish(TOPIC, outgoing,
                                                      ImmutableList.<OutgoingMessage>of())) {
      PubsubUnboundedSink<String> sink =
          new PubsubUnboundedSink<>(factory, TOPIC, StringUtf8Coder.of(), TIMESTAMP_LABEL, ID_LABEL,
                                    NUM_SHARDS, batchSize, batchBytes, Duration.standardSeconds(2),
                                    RecordIdMethod.DETERMINISTIC);
      TestPipeline p = TestPipeline.create();
      p.apply(Create.of(ImmutableList.of(DATA)))
       .apply(ParDo.of(new Stamp()))
       .apply(sink);
      p.run();
    }
    // The PubsubTestClientFactory will assert fail on close if the actual published
    // message does not match the expected publish message.
  }

  @Test
  public void sendMoreThanOneBatchByNumMessages() throws IOException {
    List<OutgoingMessage> outgoing = new ArrayList<>();
    List<String> data = new ArrayList<>();
    int batchSize = 2;
    int batchBytes = 1000;
    for (int i = 0; i < batchSize * 10; i++) {
      String str = String.valueOf(i);
      outgoing.add(new OutgoingMessage(str.getBytes(), TIMESTAMP, getRecordId(str)));
      data.add(str);
    }
    try (PubsubTestClientFactory factory =
             PubsubTestClient.createFactoryForPublish(TOPIC, outgoing,
                                                      ImmutableList.<OutgoingMessage>of())) {
      PubsubUnboundedSink<String> sink =
          new PubsubUnboundedSink<>(factory, TOPIC, StringUtf8Coder.of(), TIMESTAMP_LABEL, ID_LABEL,
                                    NUM_SHARDS, batchSize, batchBytes, Duration.standardSeconds(2),
                                    RecordIdMethod.DETERMINISTIC);
      TestPipeline p = TestPipeline.create();
      p.apply(Create.of(data))
       .apply(ParDo.of(new Stamp()))
       .apply(sink);
      p.run();
    }
    // The PubsubTestClientFactory will assert fail on close if the actual published
    // message does not match the expected publish message.
  }

  @Test
  public void sendMoreThanOneBatchByByteSize() throws IOException {
    List<OutgoingMessage> outgoing = new ArrayList<>();
    List<String> data = new ArrayList<>();
    int batchSize = 100;
    int batchBytes = 10;
    int n = 0;
    while (n < batchBytes * 10) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < batchBytes; i++) {
        sb.append(String.valueOf(n));
      }
      String str = sb.toString();
      outgoing.add(new OutgoingMessage(str.getBytes(), TIMESTAMP, getRecordId(str)));
      data.add(str);
      n += str.length();
    }
    try (PubsubTestClientFactory factory =
             PubsubTestClient.createFactoryForPublish(TOPIC, outgoing,
                                                      ImmutableList.<OutgoingMessage>of())) {
      PubsubUnboundedSink<String> sink =
          new PubsubUnboundedSink<>(factory, TOPIC, StringUtf8Coder.of(), TIMESTAMP_LABEL, ID_LABEL,
                                    NUM_SHARDS, batchSize, batchBytes, Duration.standardSeconds(2),
                                    RecordIdMethod.DETERMINISTIC);
      TestPipeline p = TestPipeline.create();
      p.apply(Create.of(data))
       .apply(ParDo.of(new Stamp()))
       .apply(sink);
      p.run();
    }
    // The PubsubTestClientFactory will assert fail on close if the actual published
    // message does not match the expected publish message.
  }

  // TODO: We would like to test that failed Pubsub publish calls cause the already assigned
  // (and random) record ids to be reused. However that can't be done without the test runnner
  // supporting retrying bundles.
}
