/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.store.client.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.store.HgKvIterator;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommonKvStreamObserverTest {

    private static final Logger LOG = LoggerFactory.getLogger(CommonKvStreamObserverTest.class);

    @Test
    public void testCompletedEmptyStreamSkipsTimedPoll() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        CommonKvStreamObserver<List<Integer>, Integer> observer = observer(queue);
        observer.onCompleted();
        StreamKvIterator<Integer> stream = stream(observer);
        assertFalse(stream.hasNext());
        stream.close();
        assertEquals("completed empty stream must not enter timed poll", 0, queue.emptyTimedPolls.get());
    }

    @Test
    public void testCompletedSingleStreamAndCloseSkipEmptyTimedPoll() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        CommonKvStreamObserver<List<Integer>, Integer> observer = observer(queue);
        observer.onNext(Collections.singletonList(1));
        observer.onCompleted();
        StreamKvIterator<Integer> stream = stream(observer);
        assertTrue(stream.hasNext());
        assertEquals(Integer.valueOf(1), stream.next());
        assertFalse(stream.hasNext());
        assertFalse(stream.hasNext());
        stream.close();
        assertEquals("exhausted terminal stream must not enter empty timed poll", 0, queue.emptyTimedPolls.get());
    }

    @Test
    public void testCompletedThreeStreamsMergeAndCloseSkipEmptyTimedPoll() throws Exception {
        List<RecordingQueue> queues = new ArrayList<>();
        MultiStreamIterator<Integer> merged = merged(queues);
        List<Integer> actual = drain(merged);
        merged.close();
        assertEquals(Arrays.asList(1, 2, 3), actual);
        assertEquals("merged terminal streams and close must not enter empty timed poll", 0,
                     queues.stream().mapToInt(queue -> queue.emptyTimedPolls.get()).sum());
    }

    @Test(timeout = 5000)
    public void testFinalBatchPublishedAtTerminalBoundaryIsNotLost() throws Exception {
        CountDownLatch boundary = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        GatedQueue queue = new GatedQueue(boundary, published);
        CommonKvStreamObserver<List<Integer>, Integer> observer = observer(queue);
        StreamKvIterator<Integer> stream = stream(observer);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                assertTrue("consumer must reach terminal/queue boundary", boundary.await(2, TimeUnit.SECONDS));
                observer.onNext(Collections.singletonList(7));
                observer.onCompleted();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                published.countDown();
            }
        }, "publish-final-client-batch");
        producer.start();
        try {
            assertTrue("final batch must remain visible when terminal state changes", stream.hasNext());
            assertEquals(Integer.valueOf(7), stream.next());
            assertFalse(stream.hasNext());
            assertEquals(null, failure.get());
        } finally {
            boundary.countDown();
            published.countDown();
            producer.join(2000);
            assertFalse("producer must finish", producer.isAlive());
            stream.close();
        }
    }

    @Test(timeout = 20000)
    public void testRealClientTerminalBenchmarks() throws Exception {
        long[] single = new long[10];
        long[] merged = new long[10];
        long[] closed = new long[10];
        for (int i = 0; i < single.length; i++) {
            CommonKvStreamObserver<List<Integer>, Integer> observer = observer(new RecordingQueue());
            observer.onNext(Collections.singletonList(1));
            observer.onCompleted();
            StreamKvIterator<Integer> stream = stream(observer);
            assertTrue(stream.hasNext());
            assertEquals(Integer.valueOf(1), stream.next());
            long started = System.nanoTime();
            assertFalse(stream.hasNext());
            single[i] = System.nanoTime() - started;
            stream.close();

            MultiStreamIterator<Integer> aggregate = merged(new ArrayList<>());
            started = System.nanoTime();
            assertEquals(Arrays.asList(1, 2, 3), drain(aggregate));
            merged[i] = System.nanoTime() - started;
            started = System.nanoTime();
            aggregate.close();
            closed[i] = System.nanoTime() - started;
        }
        LOG.info("Real client terminal median micros: single={}, merged3={}, close={}",
                 medianMicros(single), medianMicros(merged), medianMicros(closed));
    }

    private static long medianMicros(long[] samples) {
        Arrays.sort(samples);
        return TimeUnit.NANOSECONDS.toMicros(samples[samples.length / 2]);
    }

    private static List<Integer> drain(HgKvIterator<Integer> stream) {
        List<Integer> values = new ArrayList<>();
        while (stream.hasNext()) {
            values.add(stream.next());
        }
        return values;
    }

    private static MultiStreamIterator<Integer> merged(List<RecordingQueue> queues) throws Exception {
        List<HgKvIterator<Integer>> streams = new ArrayList<>();
        for (int value : Arrays.asList(1, 2, 3)) {
            RecordingQueue queue = new RecordingQueue();
            queues.add(queue);
            CommonKvStreamObserver<List<Integer>, Integer> observer = observer(queue);
            observer.onNext(Collections.singletonList(value));
            observer.onCompleted();
            streams.add(stream(observer));
        }
        return new MultiStreamIterator<>(streams);
    }

    private static StreamKvIterator<Integer> stream(CommonKvStreamObserver<List<Integer>, Integer> observer) {
        return new StreamKvIterator<>(ignored -> observer.clear(), observer::consume);
    }

    private static CommonKvStreamObserver<List<Integer>, Integer> observer(RecordingQueue queue) throws Exception {
        CommonKvStreamObserver<List<Integer>, Integer> observer =
                new CommonKvStreamObserver<>(List::iterator, ignored -> ResultState.FINISHED);
        observer.setRequestSender(ignored -> { });
        observer.setTransferComplete(ignored -> { });
        Field field = CommonKvStreamObserver.class.getDeclaredField("queue");
        field.setAccessible(true);
        field.set(observer, queue);
        return observer;
    }

    private static class RecordingQueue extends LinkedBlockingQueue<Iterator<Integer>> {

        private static final long serialVersionUID = 1L;
        final AtomicInteger emptyTimedPolls = new AtomicInteger();

        @Override
        public Iterator<Integer> poll(long timeout, TimeUnit unit) throws InterruptedException {
            if (super.isEmpty()) {
                this.emptyTimedPolls.incrementAndGet();
            }
            return super.poll(timeout, unit);
        }
    }

    private static final class GatedQueue extends RecordingQueue {

        private static final long serialVersionUID = 1L;
        private final CountDownLatch boundary;
        private final CountDownLatch published;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        private GatedQueue(CountDownLatch boundary, CountDownLatch published) {
            this.boundary = boundary;
            this.published = published;
        }

        private void publishAtBoundary() {
            if (this.armed.compareAndSet(true, false)) {
                this.boundary.countDown();
                try {
                    assertTrue("final batch must be published", this.published.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
        }

        @Override
        public boolean isEmpty() {
            boolean empty = super.isEmpty();
            this.publishAtBoundary();
            return empty;
        }

        @Override
        public Iterator<Integer> poll(long timeout, TimeUnit unit) throws InterruptedException {
            this.publishAtBoundary();
            return super.poll(timeout, unit);
        }
    }
}
