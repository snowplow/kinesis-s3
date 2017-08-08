/**
 * Copyright (c) 2014-2016 Snowplow Analytics Ltd.
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
package sinks


// NSQ
import com.github.brainlag.nsq.NSQProducer

/**
 * NSQ sink
 */
class NsqSink(config: S3LoaderNsqConfig) extends ISink {
  
  private val producer = new NSQProducer().addAddress(config.nsqHost, config.nsqdPort).start();
    
  /**
   * Write a record to the Kinesis stream
   *
   * @param output The string record to write
   * @param key Unused parameter which exists to extend ISink
   * @param good Unused parameter which exists to extend ISink
   */
  override def store(output: String, key: Option[String], good: Boolean): Unit = {
    producer.produce(config.nsqBadTopicName, output.getBytes())      
  }
}

