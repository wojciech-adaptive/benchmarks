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

import io.aeron.archive.Archive;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusterMarkFile;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.IoUtil;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.agrona.concurrent.SystemEpochClock;

import java.io.File;
import java.nio.file.Paths;
import java.util.Properties;

import static io.aeron.cluster.codecs.mark.ClusterComponentType.CONSENSUS_MODULE;
import static io.aeron.cluster.codecs.mark.ClusterComponentType.CONTAINER;
import static io.aeron.cluster.service.ClusteredServiceContainer.Configuration.LIVENESS_TIMEOUT_MS;
import static org.agrona.PropertyAction.PRESERVE;
import static org.agrona.PropertyAction.REPLACE;
import static org.agrona.SystemUtil.getSizeAsLong;
import static uk.co.real_logic.benchmarks.aeron.remote.AeronUtil.rethrowingErrorHandler;
import static uk.co.real_logic.benchmarks.aeron.remote.AeronUtil.DEFAULT_SNAPSHOT_SIZE;
import static uk.co.real_logic.benchmarks.aeron.remote.AeronUtil.SNAPSHOT_SIZE_PROP_NAME;
import static uk.co.real_logic.benchmarks.util.PropertiesUtil.loadPropertiesFiles;
import static uk.co.real_logic.benchmarks.util.PropertiesUtil.mergeWithSystemProperties;

public final class ClusterNode
{
    public static void main(final String[] args)
    {
        mergeWithSystemProperties(PRESERVE, loadPropertiesFiles(new Properties(), REPLACE, args));

        final Archive.Context archiveContext = new Archive.Context()
            .deleteArchiveOnStart(true)
            .recordingEventsEnabled(false);

        final String aeronDirectoryName = archiveContext.aeronDirectoryName();
        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlResponseStreamId(archiveContext.localControlStreamId())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirectoryName);

        final EpochClock epochClock = SystemEpochClock.INSTANCE;
        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .errorHandler(rethrowingErrorHandler("consensus-module"))
            .archiveContext(aeronArchiveContext.clone())
            .aeronDirectoryName(aeronDirectoryName)
            .epochClock(epochClock);

        consensusModuleContext.clusterMarkFile(new ClusterMarkFile(
            new File(aeronDirectoryName, ClusterMarkFile.FILENAME),
            CONSENSUS_MODULE,
            consensusModuleContext.errorBufferLength(),
            epochClock,
            LIVENESS_TIMEOUT_MS));

        final ClusteredServiceContainer.Context serviceContainerContext = new ClusteredServiceContainer.Context()
            .clusteredService(new EchoClusteredService(getSizeAsLong(SNAPSHOT_SIZE_PROP_NAME, DEFAULT_SNAPSHOT_SIZE)))
            .errorHandler(rethrowingErrorHandler("service-container"))
            .archiveContext(aeronArchiveContext.clone())
            .aeronDirectoryName(aeronDirectoryName)
            .clusterDirectoryName(consensusModuleContext.clusterDirectoryName())
            .epochClock(epochClock);

        serviceContainerContext.clusterMarkFile(new ClusterMarkFile(
            new File(aeronDirectoryName, ClusterMarkFile.markFilenameForService(serviceContainerContext.serviceId())),
            CONTAINER,
            serviceContainerContext.errorBufferLength(),
            epochClock,
            LIVENESS_TIMEOUT_MS));

        IoUtil.delete(Paths.get(consensusModuleContext.clusterDirectoryName()).toFile(), false);

        try (Archive archive = Archive.launch(archiveContext);
            ConsensusModule consensusModule = ConsensusModule.launch(consensusModuleContext);
            ClusteredServiceContainer clusteredServiceContainer = ClusteredServiceContainer.launch(
                serviceContainerContext))
        {
            new ShutdownSignalBarrier().await();
        }
    }
}
