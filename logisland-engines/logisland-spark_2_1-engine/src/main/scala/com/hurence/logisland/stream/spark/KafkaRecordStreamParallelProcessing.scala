/**
  * Copyright (C) 2016 Hurence (support@hurence.com)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package com.hurence.logisland.stream.spark

import java.util
import java.util.Collections

import com.hurence.logisland.component.PropertyDescriptor
import com.hurence.logisland.record.{FieldDictionary, Record, RecordUtils}
import com.hurence.logisland.util.processor.ProcessorMetrics
import com.hurence.logisland.util.record.RecordSchemaUtil
import org.apache.avro.Schema
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.errors.OffsetOutOfRangeException
import org.apache.spark.TaskContext
import org.apache.spark.groupon.metrics.{SparkMeter, UserMetricsSystem}
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.kafka010.{CanCommitOffsets, HasOffsetRanges, OffsetRange}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._


object KafkaRecordStreamParallelProcessing {

}

class KafkaRecordStreamParallelProcessing extends AbstractKafkaRecordStream {
    val logger = LoggerFactory.getLogger(KafkaRecordStreamParallelProcessing.getClass.getName)

    override def getSupportedPropertyDescriptors: util.List[PropertyDescriptor] = {
        val descriptors: util.List[PropertyDescriptor] = new util.ArrayList[PropertyDescriptor]

        descriptors.addAll(super.getSupportedPropertyDescriptors())

        descriptors.add(KafkaRecordStreamSQLAggregator.MAX_RESULTS_COUNT)
        descriptors.add(KafkaRecordStreamSQLAggregator.SQL_QUERY)
        Collections.unmodifiableList(descriptors)
    }

    /**
      * launch the chain of processing for each partition of the RDD in parallel
      *
      * @param rdd
      */
    override def process(rdd: RDD[ConsumerRecord[Array[Byte], Array[Byte]]]): Option[Array[OffsetRange]] = {
        if (!rdd.isEmpty()) {
            // Cast the rdd to an interface that lets us get an array of OffsetRange
            val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges

            val inputTopics = streamContext.getPropertyValue(AbstractKafkaRecordStream.INPUT_TOPICS).asString
            val outputTopics = streamContext.getPropertyValue(AbstractKafkaRecordStream.OUTPUT_TOPICS).asString

            rdd.foreachPartition(partition => {
                try {
                    if (partition.nonEmpty) {

                        /**
                          * index to get the correct offset range for the rdd partition we're working on
                          * This is safe because we haven't shuffled or otherwise disrupted partitioning,
                          * and the original input rdd partitions were 1:1 with kafka partitions
                          */
                        val partitionId = TaskContext.get.partitionId()
                        val offsetRange = offsetRanges(TaskContext.get.partitionId)

                        val pipelineTimerContext = UserMetricsSystem.timer("partition." + partitionId + "." +
                            streamContext.getIdentifier + ".processingTime").time()


                        /**
                          * create serializers
                          */
                        val deserializer = getSerializer(
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.INPUT_SERIALIZER).asString,
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.AVRO_INPUT_SCHEMA).asString)
                        val serializer = getSerializer(
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.OUTPUT_SERIALIZER).asString,
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.AVRO_OUTPUT_SCHEMA).asString)
                        val errorSerializer = getSerializer(
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.ERROR_SERIALIZER).asString,
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.AVRO_OUTPUT_SCHEMA).asString)

                        /**
                          * process events by chaining output records
                          */
                        var firstPass = true
                        var incomingEvents: util.Collection[Record] = Collections.emptyList()
                        var outgoingEvents: util.Collection[Record] = Collections.emptyList()
                        val processingMetrics: util.Collection[Record] = new util.ArrayList[Record]()

                        streamContext.getProcessContexts.foreach(processorContext => {
                            val startTime = System.currentTimeMillis()
                            val processor = processorContext.getProcessor

                            val processorTimerContext = UserMetricsSystem.timer(
                                processorContext.getName + ".processingTime").time()
                            /**
                              * convert incoming Kafka messages into Records
                              * if there's no serializer we assume that we need to compute a Record from K/V
                              */
                            if (firstPass) {
                                incomingEvents = if (
                                    streamContext.getPropertyValue(AbstractKafkaRecordStream.INPUT_SERIALIZER).asString
                                        == AbstractKafkaRecordStream.NO_SERIALIZER.getValue) {
                                    // parser
                                    partition.map(rawMessage => {
                                        val key = if (rawMessage.key() != null) new String(rawMessage.key()) else ""
                                        val value = if (rawMessage.value() != null) new String(rawMessage.value()) else ""
                                        RecordUtils.getKeyValueRecord(key, value)
                                    }).toList
                                } else {
                                    // processor
                                    deserializeRecords(partition, deserializer)
                                }

                                firstPass = false
                            } else {
                                incomingEvents = outgoingEvents
                            }

                            /**
                              * process incoming events
                              */
                            if (processor.hasControllerService) {
                                val controllerServiceLookup = controllerServiceLookupSink.value.getControllerServiceLookup()
                                processorContext.addControllerServiceLookup(controllerServiceLookup)
                            }
                            processor.init(processorContext)
                            outgoingEvents = processor.process(processorContext, incomingEvents)

                            /**
                              * send metrics if requested
                              */
                            processingMetrics.addAll(ProcessorMetrics.computeMetrics(
                                appName,
                                processorContext.getName,
                                inputTopics,
                                outputTopics,
                                partitionId,
                                incomingEvents,
                                outgoingEvents,
                                offsetRange.fromOffset,
                                offsetRange.untilOffset,
                                System.currentTimeMillis() - startTime))

                            processorTimerContext.stop()
                        })


                        /**
                          * Do we make records compliant with a given Avro schema ?
                          */
                        if (streamContext.getPropertyValue(AbstractKafkaRecordStream.AVRO_OUTPUT_SCHEMA).isSet) {
                            try {
                                val strSchema = streamContext.getPropertyValue(AbstractKafkaRecordStream.AVRO_OUTPUT_SCHEMA).asString()
                                val parser = new Schema.Parser
                                val schema = parser.parse(strSchema)

                                outgoingEvents = outgoingEvents.map(record => RecordSchemaUtil.convertToValidRecord(record, schema))
                            } catch {
                                case t: Throwable =>
                                    logger.warn("something wrong while converting records " +
                                        "to valid accordingly to provide Avro schema " + t.getMessage)
                            }

                        }

                        /**
                          * push outgoing events and errors to Kafka
                          */

                        kafkaSink.value.produce(
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.OUTPUT_TOPICS).asString,
                            outgoingEvents.toList,
                            serializer
                        )

                        kafkaSink.value.produce(
                            streamContext.getPropertyValue(AbstractKafkaRecordStream.ERROR_TOPICS).asString,
                            outgoingEvents.filter(r => r.hasField(FieldDictionary.RECORD_ERRORS)).toList,
                            errorSerializer
                        )

                        processingMetrics.foreach(metrics => {
                            UserMetricsSystem.meter("NumIncomingMessages").mark(metrics.getField("num_incoming_messages").asLong())
                            UserMetricsSystem.meter("NumIncomingRecords").mark(metrics.getField("num_incoming_records").asLong())
                            UserMetricsSystem.meter("NumOutgoingMessages").mark(metrics.getField("num_outgoing_records").asLong())
                            UserMetricsSystem.meter("NumErrorRecords").mark(metrics.getField("num_errors_records").asLong())
                            UserMetricsSystem.meter("ErrorPercentage").mark(metrics.getField("error_percentage").asLong())
                            UserMetricsSystem.meter("AverageBytesPerField").mark(metrics.getField("average_bytes_per_field").asLong())
                            UserMetricsSystem.meter("AverageBytesPerSecond").mark(metrics.getField("average_bytes_per_second").asLong())
                            UserMetricsSystem.meter("AverageNumRecordsPerSecond").mark(metrics.getField("average_num_records_per_second").asLong())
                            UserMetricsSystem.meter("AverageFieldsPerRecord").mark(metrics.getField("average_fields_per_record").asLong())
                            UserMetricsSystem.meter("AverageBytesPerRecord").mark(metrics.getField("average_bytes_per_record").asLong())
                            UserMetricsSystem.meter("TotalBytes").mark(metrics.getField("total_bytes").asLong())
                            UserMetricsSystem.meter("TotalFields").mark(metrics.getField("total_fields").asLong())
                            UserMetricsSystem.meter("TotalProcessingTime").mark(metrics.getField("total_processing_time_in_ms").asLong())
                        })

                        /**
                          * save latest offset to Zookeeper
                          */
                        zkSink.value.saveOffsetRangesToZookeeper(appName, offsetRange)
                        pipelineTimerContext.stop()
                    }
                }
                catch {
                    case ex: OffsetOutOfRangeException =>
                        val brokerList = streamContext.getPropertyValue(AbstractKafkaRecordStream.KAFKA_METADATA_BROKER_LIST).asString
                        val latestOffsetsString = zkSink.value.loadOffsetRangesFromZookeeper(
                            brokerList,
                            appName,
                            inputTopics.split(",").toSet)
                            .map(t => s"${t._1.topic}_${t._1.partition}:${t._2}")
                            .mkString(", ")
                        val offestsString = offsetRanges
                            .map(o => s"${o.topic}_${o.partition}:${o.fromOffset}/${o.untilOffset}")
                            .mkString(", ")
                        logger.error(s"exception : ${ex.toString}")
                        logger.error(s"unable to process partition. current Offsets $offestsString latest offsets $latestOffsetsString")

                }
            }
            )
            return Some(offsetRanges)
        }

        None
    }

}


