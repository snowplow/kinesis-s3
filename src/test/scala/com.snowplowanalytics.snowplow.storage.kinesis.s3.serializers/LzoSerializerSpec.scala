/*
 * Copyright (c) 2015 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow
package storage.kinesis.s3.serializers

// Java
import java.util.Properties
import java.io.{
  File,
  FileInputStream,
  FileOutputStream,
  BufferedInputStream
}

// AWS libs
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider

// AWS Kinesis Connector libs
import com.amazonaws.services.kinesis.connectors.{
  KinesisConnectorConfiguration,
  UnmodifiableBuffer
}
import com.amazonaws.services.kinesis.connectors.impl.BasicMemoryBuffer

// Elephant Bird
import com.twitter.elephantbird.mapreduce.io.RawBlockReader

// Apache Thrift
import org.apache.thrift.{
  TSerializer,
  TDeserializer
}

// Scalaz
import scalaz._
import Scalaz._

// Scala
import scala.sys.process._
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

// Snowplow
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

// Specs2
import org.specs2.mutable.Specification
import org.specs2.scalaz.ValidationMatchers

/**
 * Tests serialization and LZO compression of CollectorPayloads
 */
class LzoSerializerSpec extends Specification with ValidationMatchers {

  "The LzoSerializer" should {
    "correctly serialize and compress a list of CollectorPayloads" in {

      val serializer = new TSerializer
      val deserializer = new TDeserializer

      val decompressedFilename = "/tmp/kinesis-s3-sink-test-lzo"

      val compressedFilename = decompressedFilename + ".lzo"

      def cleanup() = List(compressedFilename, decompressedFilename).foreach(new File(_).delete())

      cleanup()

      val inputEvents = List(
        new CollectorPayload("A", "B", 1000, "a", "b").success,
        new CollectorPayload("X", "Y", 2000, "x", "y").success)

      val binaryInputs = inputEvents.map(e => e.map(x => serializer.serialize(x)))

      val serializationResult = LzoSerializer.serialize(binaryInputs, decompressedFilename)

      val lzoOutput = serializationResult.namedStreams.head.stream

      lzoOutput.writeTo(new FileOutputStream(compressedFilename))

      s"lzop -d $compressedFilename" !!

      val input = new BufferedInputStream(new FileInputStream(decompressedFilename))
      val reader = new RawBlockReader(input)

      cleanup()

      inputEvents map {e => {
          val rawResult = reader.readNext()
          val target = new CollectorPayload
          deserializer.deserialize(target, rawResult)

          target.success must_== e
        }
      }
    }
  }
}
