/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core;

import com.google.common.io.ByteStreams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EventParameterMemoryLeakTest {

    @BeforeClass
    public static void beforeClass() {
        System.setProperty("log4j2.enable.threadlocals", "true");
        System.setProperty("log4j2.enable.direct.encoders", "true");
        System.setProperty("log4j2.is.webapp", "false");
        System.setProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY, "EventParameterMemoryLeakTest.xml");
    }

    @Test
    public void testParametersAreNotLeaked() throws Exception {
        final File file = new File("target", "EventParameterMemoryLeakTest.log");
        assertTrue("Deleted old file before test", !file.exists() || file.delete());

        final Logger log = LogManager.getLogger("com.foo.Bar");
        CountDownLatch latch = new CountDownLatch(1);
        log.info("Message with parameter {}", new ParameterObject("paramValue", latch));
        CoreLoggerContexts.stopLoggerContext(file);
        final BufferedReader reader = new BufferedReader(new FileReader(file));
        final String line1 = reader.readLine();
        final String line2 = reader.readLine();
        reader.close();
        file.delete();
        assertThat(line1, containsString("Message with parameter paramValue"));
        assertNull("Expected only a single line", line2);
        GarbageCollectionHelper gcHelper = new GarbageCollectionHelper();
        gcHelper.run();
        try {
            assertTrue("Parameter should have been garbage collected", latch.await(30, TimeUnit.SECONDS));
        } finally {
            gcHelper.close();
        }
    }

    private static final class ParameterObject {
        private final String value;
        private final CountDownLatch latch;
        ParameterObject(String value, CountDownLatch latch) {
            this.value = value;
            this.latch = latch;
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        protected void finalize() throws Throwable {
            latch.countDown();
            super.finalize();
        }
    }

    private static final class GarbageCollectionHelper implements Closeable, Runnable {
        private static final OutputStream sink = ByteStreams.nullOutputStream();
        public final AtomicBoolean running = new AtomicBoolean();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final Thread gcThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running.get()) {
                        // Allocate data to help suggest a GC
                        try {
                            // 1mb of heap
                            sink.write(new byte[1024 * 1024]);
                        } catch (IOException ignored) {
                        }
                        // May no-op depending on the jvm configuration
                        System.gc();
                    }
                } finally {
                    latch.countDown();
                }
            }
        });

        @Override
        public void run() {
            if (running.compareAndSet(false, true)) {
                gcThread.start();
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                assertTrue("GarbageCollectionHelper did not shut down cleanly",
                        latch.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
