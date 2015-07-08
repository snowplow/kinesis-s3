[ ![Release] [release-image] ] [releases] [ ![License] [license-image] ] [license]

## Overview

The Kinesis S3 Sink consumes records from an [Amazon Kinesis][kinesis] stream, and writes them to S3.

There are 2 file format supported:
 * LZO
 * GZip

### LZO

The records are treated as raw byte arrays. [Elephant Bird's][elephant-bird] `BinaryBlockWriter` class is used to serialize them as a [Protocol Buffers][protobufs] array (so it is clear where one record ends and the next begins) before compressing them.

The compression process generates both compressed .lzo files and small .lzo.index files ([splittable LZO][hadoop-lzo]). Each index file contain the byte offsets of the LZO blocks in the corresponding compressed file, meaning that the blocks can be processed in parallel.

### GZip

The records are treated as tab separated rows. New lines are used to separate records written to a file. This format should only be used with Kinesis Enriched stream.

## Prerequisites

You must have `lzop` and `lzop-dev` installed. In Ubuntu, install them like this:

    $ sudo apt-get install lzop liblzo2-dev

## Building

Assuming you already have [SBT 0.13.0] [sbt] installed:

    $ git clone git://github.com/snowplow/snowplow.git
    $ cd 4-storage/kinesis-lzo-s3-sink
    $ sbt compile

## Usage

The Kinesis S3 LZO Sink has the following command-line interface:

```
snowplow-kinesis-s3: Version 0.3.0. Copyright (c) 2014-2015, Snowplow Analytics
Ltd.

Usage: snowplow-lzo-s3-sink [OPTIONS]

OPTIONS
--config filename
                   Configuration file.
```

## Running

Create your own config file:

    $ cp src/main/resources/config.hocon.sample my.conf

Edit it and update the AWS credentials:

```js
aws {
  access-key: "default"
  secret-key: "default"
}
```

Next, start the sink, making sure to specify your new config file:

    $ sbt "run --config my.conf"

## Find out more

| Technical Docs              | Setup Guide           | Roadmap & Contributing               |         
|-----------------------------|-----------------------|--------------------------------------|
| ![i1] [techdocs-image]      | ![i2] [setup-image]   | ![i3] [roadmap-image]                |
| [Technical Docs] [techdocs] | [Setup Guide] [setup] | _coming soon_                        |

## Copyright and license

Kinesis S3 is copyright 2014-2015 Snowplow Analytics Ltd.

Licensed under the [Apache License, Version 2.0] [license] (the "License");
you may not use this software except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

[release-image]: http://img.shields.io/badge/release-0.3.0-blue.svg?style=flat
[releases]: https://github.com/snowplow/kinesis-s3/releases

[license-image]: http://img.shields.io/badge/license-Apache--2-blue.svg?style=flat
[license]: http://www.apache.org/licenses/LICENSE-2.0

[kinesis]: http://aws.amazon.com/kinesis/
[snowplow]: http://snowplowanalytics.com
[hadoop-lzo]: https://github.com/twitter/hadoop-lzo
[protobufs]: https://github.com/google/protobuf/
[elephant-bird]: https://github.com/twitter/elephant-bird/
[s3]: http://aws.amazon.com/s3/
[sbt]: http://typesafe.artifactoryonline.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.13.0/sbt-launch.jar

[setup]: https://github.com/snowplow/snowplow/wiki/kinesis-lzo-s3-sink-setup
[techdocs]: https://github.com/snowplow/snowplow/wiki/kinesis-lzo-s3-sink

[techdocs-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/techdocs.png
[setup-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/setup.png
[roadmap-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/roadmap.png
