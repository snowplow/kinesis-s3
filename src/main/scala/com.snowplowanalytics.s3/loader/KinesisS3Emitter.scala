/*
 * Copyright (c) 2014-2015 Snowplow Analytics Ltd. All rights reserved.
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

// Scala
import scala.collection.JavaConverters._

// Java libs
import java.util.Calendar
import java.text.SimpleDateFormat

//AWS libs
import com.amazonaws.auth.AWSCredentialsProvider

// AWS Kinesis connector libs
import com.amazonaws.services.kinesis.connectors.UnmodifiableBuffer
import com.amazonaws.services.kinesis.connectors.interfaces.IEmitter

// Scala
import scala.collection.JavaConversions._

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

// This project
import sinks._
import serializers._
import model._

/**
 * Emitter for flushing Kinesis event data to S3.
 *
 * Once the buffer is full, the emit function is called.
 */
class KinesisS3Emitter(
  s3Config: S3Config,
  provider: AWSCredentialsProvider,
  badSink: ISink, 
  serializer: ISerializer, 
  maxConnectionTime: Long, 
  tracker: Option[Tracker]
) extends IEmitter[EmitterInput]  {

  val s3Emitter = new S3Emitter(s3Config, provider, badSink, maxConnectionTime, tracker)
  val calendar = Calendar.getInstance()

  /**
   * Determines the filename in S3, which is the corresponding
   * Kinesis sequence range of records in the file.
   */
  protected def getBaseFilename(firstSeq: String, lastSeq: String): String = {
    val date = calendar.getTime()
    val dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    val (yearFormat, monthFormat, dayFormat) = ( new SimpleDateFormat("yyyy"), new SimpleDateFormat("MM"), new SimpleDateFormat("dd"))

    var prefix = ""
    if (s3Config.partitioningFormat == "flat") {
      prefix = s"${dateFormat.format(date)}-"
    } else if (s3Config.partitioningFormat == "hive") {
      prefix = s"Year=${yearFormat.format(date)}/Month=${monthFormat.format(date)}/Day=${dayFormat.format(date)}/"
    }

    return s"${prefix}${firstSeq}-${lastSeq}"
  }

  /**
   * Reads items from a buffer and saves them to s3.
   *
   * This method is expected to return a List of items that
   * failed to be written out to S3, which will be sent to
   * a Kinesis stream for bad events.
   *
   * @param buffer BasicMemoryBuffer containing EmitterInputs
   * @return list of inputs which failed transformation
   */
  override def emit(buffer: UnmodifiableBuffer[EmitterInput]): java.util.List[EmitterInput] = {

    s3Emitter.log.info(s"Flushing buffer with ${buffer.getRecords.size} records.")

    val records = buffer.getRecords().asScala.toList
    val baseFilename = getBaseFilename(buffer.getFirstSequenceNumber, buffer.getLastSequenceNumber)
    val serializationResults = serializer.serialize(records, baseFilename)
    val (successes, failures) = serializationResults.results.partition(_.isSuccess)

    s3Emitter.log.info(s"Successfully serialized ${successes.size} records out of ${successes.size + failures.size}")

    val connectionAttemptStartTime = System.currentTimeMillis()

    if (successes.size > 0) {
      serializationResults.namedStreams.foreach { s3Emitter.attemptEmit(_, connectionAttemptStartTime) }
      failures
    } else {
      failures
    }
  }

  /**
   * Closes the client when the KinesisConnectorRecordProcessor is shut down
   */
  override def shutdown(): Unit =
    s3Emitter.client.shutdown

  /**
   * Sends records which fail deserialization or compression
   * to Kinesis with an error message
   *
   * @param records List of failed records to send to Kinesis
   */
  override def fail(records: java.util.List[EmitterInput]): Unit =
    s3Emitter.sendFailures(records)
}
