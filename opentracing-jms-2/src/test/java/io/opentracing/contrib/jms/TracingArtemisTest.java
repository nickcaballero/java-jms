/*
 * Copyright 2017-2018 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.jms;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.opentracing.contrib.jms.common.TracingMessageConsumer;
import io.opentracing.contrib.jms.common.TracingMessageListener;
import io.opentracing.contrib.jms.common.TracingMessageUtils;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.ThreadLocalScopeManager;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.impl.ActiveMQServerImpl;
import org.apache.activemq.artemis.jms.client.ActiveMQJMSConnectionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TracingArtemisTest {

  private final MockTracer mockTracer = new MockTracer(new ThreadLocalScopeManager(),
      MockTracer.Propagator.TEXT_MAP);

  private ActiveMQServer server;
  private Connection connection;
  private Session session;


  @Before
  public void before() throws Exception {
    mockTracer.reset();

    org.apache.activemq.artemis.core.config.Configuration configuration = new ConfigurationImpl();

    HashSet<TransportConfiguration> transports = new HashSet<>();
    transports.add(new TransportConfiguration(InVMAcceptorFactory.class.getName()));
    configuration.setAcceptorConfigurations(transports);
    configuration.setSecurityEnabled(false);

    File targetDir = new File(System.getProperty("user.dir") + "/target");
    configuration.setBrokerInstance(targetDir);

    server = new ActiveMQServerImpl(configuration);
    server.start();
    ActiveMQJMSConnectionFactory connectionFactory = new ActiveMQJMSConnectionFactory("vm://0");
    connection = connectionFactory.createConnection();

    connection.start();

    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
  }

  @After
  public void after() throws Exception {
    session.close();
    connection.close();
    server.stop();
  }


  @Test
  public void sendAndReceive() throws Exception {
    Queue queue = session.createQueue("TEST.FOO");

    MessageProducer messageProducer = session.createProducer(queue);

    // Instrument MessageProducer with TracingMessageProducer
    TracingMessageProducer producer =
        new TracingMessageProducer(messageProducer, mockTracer);

    MessageConsumer messageConsumer = session.createConsumer(queue);

    // Instrument MessageConsumer with TracingMessageConsumer
    TracingMessageConsumer consumer = new TracingMessageConsumer(messageConsumer, mockTracer);

    TextMessage message = session.createTextMessage("Hello world");

    producer.send(message);

    TextMessage received = (TextMessage) consumer.receive(5000);
    assertEquals("Hello world", received.getText());

    List<MockSpan> mockSpans = mockTracer.finishedSpans();
    assertEquals(2, mockSpans.size());

    checkSpans(mockSpans);
    assertNull(mockTracer.activeSpan());
  }

  @Test
  public void sendAndReceiveInListener() throws Exception {
    Destination destination = session.createQueue("TEST.FOO");

    MessageProducer messageProducer = session.createProducer(destination);

    // Instrument MessageProducer with TracingMessageProducer
    TracingMessageProducer producer =
        new TracingMessageProducer(messageProducer, mockTracer);

    MessageConsumer messageConsumer = session.createConsumer(destination);

    final CountDownLatch countDownLatch = new CountDownLatch(1);
    // Instrument MessgaeListener with TraceMessageListener
    MessageListener messageListener = new TracingMessageListener(
        new MessageListener() {
          @Override
          public void onMessage(Message message) {
            countDownLatch.countDown();
          }
        }, mockTracer);

    messageConsumer.setMessageListener(messageListener);

    TextMessage message = session.createTextMessage("Hello world");

    producer.send(message);
    countDownLatch.await(15, TimeUnit.SECONDS);

    await().atMost(15, TimeUnit.SECONDS).until(reportedSpansSize(), equalTo(2));

    List<MockSpan> mockSpans = mockTracer.finishedSpans();
    assertEquals(2, mockSpans.size());

    checkSpans(mockSpans);

    assertNull(mockTracer.activeSpan());
  }

  private void checkSpans(List<MockSpan> mockSpans) {
    for (MockSpan mockSpan : mockSpans) {
      assertTrue(mockSpan.tags().get(Tags.SPAN_KIND.getKey()).equals(Tags.SPAN_KIND_CONSUMER)
          || mockSpan.tags().get(Tags.SPAN_KIND.getKey()).equals(Tags.SPAN_KIND_PRODUCER));
      assertEquals(TracingMessageUtils.COMPONENT_NAME,
          mockSpan.tags().get(Tags.COMPONENT.getKey()));
      assertEquals(0, mockSpan.generatedErrors().size());
      String operationName = mockSpan.operationName();
      assertTrue(operationName.equals(TracingMessageUtils.OPERATION_NAME_SEND)
          || operationName.equals(TracingMessageUtils.OPERATION_NAME_RECEIVE));
    }
  }

  private Callable<Integer> reportedSpansSize() {
    return new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        return mockTracer.finishedSpans().size();
      }
    };
  }

}
