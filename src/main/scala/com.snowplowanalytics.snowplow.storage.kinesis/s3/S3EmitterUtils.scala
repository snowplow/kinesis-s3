/**
 * Copyright (c) 2014-2017 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */

package com.snowplowanalytics.snowplow.storage.kinesis.s3


//Java
import java.io.ByteArrayInputStream

// Scala
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import scala.collection.JavaConversions._

// SLF4j
import org.slf4j.LoggerFactory

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

// Scalaz
import scalaz._
import Scalaz._

// AWS libs
import com.amazonaws.AmazonServiceException
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ObjectMetadata

// AWS Kinesis connector libs
import com.amazonaws.services.kinesis.connectors.{
  KinesisConnectorConfiguration
}

// json4s
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._

// Joda-Time
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat

// This project
import sinks._
import serializers._


class S3EmitterUtils(
  config: KinesisConnectorConfiguration, 
  badSink: ISink,
  maxConnectionTime: Long,
  tracker: Option[Tracker]
) {

  // create Amazon S3 Client
  private val bucket = config.S3_BUCKET
  val log = LoggerFactory.getLogger(getClass)
  val client = AmazonS3ClientBuilder
    .standard()
    .withCredentials(config.AWS_CREDENTIALS_PROVIDER)
    .withEndpointConfiguration(new EndpointConfiguration(config.S3_ENDPOINT, config.REGION_NAME))
    .build()

  /**
  * The amount of time to wait in between unsuccessful index requests (in milliseconds).
  * 10 seconds = 10 * 1000 = 10000
  */
  private val BackoffPeriod = 10000L
  private val TstampFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(DateTimeZone.UTC)


  /**
  * Period between retrying sending events to S3
  *
  * @param sleepTime Length of time between tries
  */
  private def sleep(sleepTime: Long): Unit = {
    try {
    Thread.sleep(sleepTime)
    } catch {
      case e: InterruptedException => ()
    }
  }


  /**
  * Terminate the application 
  *
  * Prevents shutdown hooks from running
  */
  private def forceShutdown(): Unit = {
    log.error(s"Shutting down application as unable to connect to S3 for over $maxConnectionTime ms")
    tracker foreach {
      t =>
        SnowplowTracking.trackApplicationShutdown(t)
        sleep(5000)
      }
    Runtime.getRuntime.halt(1)
  }


  /**
  * Returns an ISO valid timestamp
  *
  * @param tstamp The Timestamp to convert
  * @return the formatted Timestamp
  */
  private def getTimestamp(tstamp: Long): String = {
    val dt = new DateTime(tstamp)
    TstampFormat.print(dt)
  }

  /**
  * Sends records which fail deserialization or compression
  * to NSQ with an error message
  *
  * @param records List of failed records to send to NSQ
  */
  def sendFailures(records: java.util.List[EmitterInput]): Unit = {
    for (Failure(record) <- records.toList) {
      log.warn(s"Record failed: $record.line")
      log.info("Sending failed record to Kinesis")
      val output = compact(render(
        ("line" -> record.line) ~ 
        ("errors" -> record.errors) ~
        ("failure_tstamp" -> getTimestamp(System.currentTimeMillis()))
      ))
      badSink.store(output, Some("key"), false)
    }
  }

  /**
  * Keep attempting to send the data to S3 until it succeeds
  *
  * @return success status of sending to S3
  */
  def attemptEmit(namedStream: NamedStream, connectionAttemptStartTime: Long): Boolean = {

    var attemptCount: Long = 1
    while (true) {
      if (attemptCount > 1 && System.currentTimeMillis() - connectionAttemptStartTime > maxConnectionTime) {
        forceShutdown()
      }

      try {
        val outputStream = namedStream.stream
        val filename = namedStream.filename
        val inputStream = new ByteArrayInputStream(outputStream.toByteArray)

        val objMeta = new ObjectMetadata()
        objMeta.setContentLength(outputStream.size.toLong)
        client.putObject(bucket, filename, inputStream, objMeta)

        return true
      } catch {
        // Retry on failure
        case ase: AmazonServiceException => {
          log.error("S3 could not process the request", ase)
          tracker match {
            case Some(t) => SnowplowTracking.sendFailureEvent(t, BackoffPeriod, connectionAttemptStartTime, attemptCount, ase.toString)
            case None => None
          }
          attemptCount = attemptCount + 1
          sleep(BackoffPeriod)
        }
        case NonFatal(e) => {
          log.error("S3Emitter threw an unexpected exception", e)
          tracker match {
            case Some(t) => SnowplowTracking.sendFailureEvent(t, BackoffPeriod, connectionAttemptStartTime, attemptCount, e.toString)
            case None => None
          }
          attemptCount = attemptCount + 1
          sleep(BackoffPeriod)
        }
      }
    }
    false
  }
}