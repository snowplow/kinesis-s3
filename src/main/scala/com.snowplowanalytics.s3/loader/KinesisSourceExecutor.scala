/*
 * Copyright (c) 2014-2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.s3.loader

// Logging
import org.slf4j.LoggerFactory

// AWS Kinesis Connector libs
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClient}
import com.amazonaws.services.kinesis.connectors.{
  KinesisConnectorConfiguration,
  KinesisConnectorExecutorBase,
  KinesisConnectorRecordProcessorFactory
}

// AWS Client Library
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker
import com.amazonaws.services.kinesis.metrics.interfaces.IMetricsFactory

// Java
import java.util.Date

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

// This project
import sinks._
import serializers._
import model._

/**
 * A worker (Runnable) class for Kinesis Connector,
 * initializes config and passes control over to [[KinesisS3Pipeline]]
 */
class KinesisSourceExecutor(
  config: KinesisConnectorConfiguration,
  initialPosition: String,
  initialTimestamp: Option[Date],
  s3Config: S3Config,
  badSink: ISink,
  serializer: ISerializer,
  maxConnectionTime: Long,
  tracker: Option[Tracker]
) extends KinesisConnectorExecutorBase[ValidatedRecord, EmitterInput] {

  val LOG = LoggerFactory.getLogger(getClass)

  initialize(config, null)

  def getKCLConfig(initialPosition: String, timestamp: Option[Date], kcc: KinesisConnectorConfiguration): KinesisClientLibConfiguration = {
      val cfg = new KinesisClientLibConfiguration(
        kcc.APP_NAME,
        kcc.KINESIS_INPUT_STREAM,
        kcc.AWS_CREDENTIALS_PROVIDER,
        kcc.WORKER_ID).withKinesisEndpoint(kcc.KINESIS_ENDPOINT)
          .withFailoverTimeMillis(kcc.FAILOVER_TIME)
          .withMaxRecords(kcc.MAX_RECORDS)
          .withIdleTimeBetweenReadsInMillis(kcc.IDLE_TIME_BETWEEN_READS)
          .withCallProcessRecordsEvenForEmptyRecordList(KinesisConnectorConfiguration.DEFAULT_CALL_PROCESS_RECORDS_EVEN_FOR_EMPTY_LIST)
          .withCleanupLeasesUponShardCompletion(kcc.CLEANUP_TERMINATED_SHARDS_BEFORE_EXPIRY)
          .withParentShardPollIntervalMillis(kcc.PARENT_SHARD_POLL_INTERVAL)
          .withShardSyncIntervalMillis(kcc.SHARD_SYNC_INTERVAL)
          .withTaskBackoffTimeMillis(kcc.BACKOFF_INTERVAL)
          .withMetricsBufferTimeMillis(kcc.CLOUDWATCH_BUFFER_TIME)
          .withMetricsMaxQueueSize(kcc.CLOUDWATCH_MAX_QUEUE_SIZE)
          .withUserAgent(kcc.APP_NAME + ","
            + kcc.CONNECTOR_DESTINATION + ","
            + KinesisConnectorConfiguration.KINESIS_CONNECTOR_USER_AGENT)
          .withRegionName(kcc.REGION_NAME)

      timestamp.filter(_ => initialPosition == "AT_TIMESTAMP")
        .map(cfg.withTimestampAtInitialPositionInStream)
        .getOrElse(cfg.withInitialPositionInStream(kcc.INITIAL_POSITION_IN_STREAM))
  }

  /**
    * Initialize the Amazon Kinesis Client Library configuration and worker with metrics factory
    *
    * @param kinesisConnectorConfiguration Amazon Kinesis connector configuration
    * @param metricFactory would be used to emit metrics in Amazon Kinesis Client Library
    */
  override def initialize(kinesisConnectorConfiguration: KinesisConnectorConfiguration, metricFactory: IMetricsFactory): Unit = {
    val workerBuilder = new Worker.Builder()
      .recordProcessorFactory(getKinesisConnectorRecordProcessorFactory())
      .kinesisClient(getKinesisClient(kinesisConnectorConfiguration))

    // If a metrics factory was specified, use it.
    worker = if (metricFactory != null) {
      workerBuilder
        .metricsFactory(metricFactory)
        .build()
    } else {
      workerBuilder.build()
    }

    LOG.info(getClass.getSimpleName + " worker created")
  }

  private def getKinesisClient(kinesisConnectorConfiguration: KinesisConnectorConfiguration): AmazonKinesis = {
    val kinesisClientLibConfiguration = getKCLConfig(initialPosition, initialTimestamp, kinesisConnectorConfiguration)

    if (!kinesisConnectorConfiguration.CALL_PROCESS_RECORDS_EVEN_FOR_EMPTY_LIST) {
      LOG.warn("The false value of callProcessRecordsEvenForEmptyList will be ignored. It must be set to true for the bufferTimeMillisecondsLimit to work correctly.")
    }

    if (kinesisConnectorConfiguration.IDLE_TIME_BETWEEN_READS > kinesisConnectorConfiguration.BUFFER_MILLISECONDS_LIMIT) {
      LOG.warn("idleTimeBetweenReads is greater than bufferTimeMillisecondsLimit. For best results, ensure that bufferTimeMillisecondsLimit is more than or equal to idleTimeBetweenReads ")
    }

    AmazonKinesisClient
      .builder()
      .withClientConfiguration(kinesisClientLibConfiguration.getKinesisClientConfiguration)
      .build()
  }

  override def getKinesisConnectorRecordProcessorFactory = {
    val pipeline = new KinesisS3Pipeline(s3Config, badSink, serializer, maxConnectionTime, tracker)
    new KinesisConnectorRecordProcessorFactory[ValidatedRecord, EmitterInput](pipeline, config)
  }
}
