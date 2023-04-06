/*
 * Copyright 2015-2023 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.benchmarks.aeron.remote;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SystemNanoClock;

import java.io.PrintStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aeron.Aeron.connect;
import static org.agrona.CloseHelper.closeAll;
import static org.agrona.PropertyAction.PRESERVE;
import static org.agrona.PropertyAction.REPLACE;
import static uk.co.real_logic.benchmarks.aeron.remote.AeronUtil.*;
import static uk.co.real_logic.benchmarks.util.PropertiesUtil.loadPropertiesFiles;
import static uk.co.real_logic.benchmarks.util.PropertiesUtil.mergeWithSystemProperties;

/**
 * Remote node which echoes original messages back to the sender.
 */
public final class EchoNode implements AutoCloseable, Runnable
{
    private final BufferClaim bufferClaim = new BufferClaim();
    private final FragmentHandler fragmentHandler;
    private final ExclusivePublication publication;
    private final Subscription subscription;
    private final AtomicBoolean running;
    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final boolean ownsAeronClient;

    EchoNode(final AtomicBoolean running)
    {
        this(running, launchEmbeddedMediaDriverIfConfigured(), connect(), true, System.out);
    }

    EchoNode(
        final AtomicBoolean running,
        final MediaDriver mediaDriver,
        final Aeron aeron,
        final boolean ownsAeronClient,
        final PrintStream out)
    {
        this.running = running;
        this.mediaDriver = mediaDriver;
        this.aeron = aeron;
        this.ownsAeronClient = ownsAeronClient;

        publication = aeron.addExclusivePublication(sourceChannel(), sourceStreamId());
        subscription = aeron.addSubscription(destinationChannel(), destinationStreamId());

        fragmentHandler = (buffer, offset, length, header) ->
        {
            long result;
            while ((result = publication.tryClaim(length, bufferClaim)) <= 0)
            {
                checkPublicationResult(result);
            }

            bufferClaim
                .flags(header.flags())
                .putBytes(buffer, offset, length)
                .commit();
        };

        awaitConnected(
            () -> subscription.isConnected() && publication.isConnected(),
            connectionTimeoutNs(),
            SystemNanoClock.INSTANCE);
    }

    public void run()
    {
        final IdleStrategy idleStrategy = idleStrategy();

        final AtomicBoolean running = this.running;

        final Image image = subscription.imageAtIndex(0);
        while (true)
        {
            final int fragments = image.poll(fragmentHandler, FRAGMENT_LIMIT);
            if (0 == fragments)
            {
                if (!running.get())
                {
                    return; // Abort execution
                }

                if (image.isClosed())
                {
                    return;  // Abort execution
                }
            }

            idleStrategy.idle(fragments);
        }
    }

    public void close()
    {
        closeAll(subscription);
        closeAll(publication);

        if (ownsAeronClient)
        {
            closeAll(aeron, mediaDriver);
        }
    }

    public static void main(final String[] args)
    {
        Thread.currentThread().setName("echo");
        mergeWithSystemProperties(PRESERVE, loadPropertiesFiles(new Properties(), REPLACE, args));

        final AtomicBoolean running = new AtomicBoolean(true);
        installSignalHandler(running);

        try (EchoNode server = new EchoNode(running))
        {
            server.run();
        }
    }
}
