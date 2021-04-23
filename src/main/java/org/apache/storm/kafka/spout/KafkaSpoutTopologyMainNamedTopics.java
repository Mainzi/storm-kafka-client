/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.apache.storm.kafka.spout;

import static org.apache.storm.kafka.spout.FirstPollOffsetStrategy.EARLIEST;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.kafka.bolt.KafkaProducerTopology;
import org.apache.storm.kafka.bolt.LengthCountBolt;
import org.apache.storm.kafka.bolt.SplitSentenceBolt;
import org.apache.storm.kafka.bolt.WordCountBolt;
import org.apache.storm.kafka.spout.KafkaSpoutRetryExponentialBackoff.TimeInterval;
import org.apache.storm.redis.bolt.RedisStoreBolt;
import org.apache.storm.redis.common.config.JedisPoolConfig;
import org.apache.storm.redis.common.mapper.RedisDataTypeDescription;
import org.apache.storm.redis.common.mapper.RedisStoreMapper;
import org.apache.storm.task.ShellBolt;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.ITuple;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;


/**
 * This example sets up 3 topologies to put data in Kafka via the KafkaBolt,
 * and shows how to set up a topology that reads from some Kafka topics using the KafkaSpout.
 */
public class KafkaSpoutTopologyMainNamedTopics {

    private static final String TOPIC_0_STREAM = "test_0_1_stream";
    public static final String TOPIC_0 = "random-sentence";
    private static final String KAFKA_LOCAL_BROKER = "localhost:9092";

    public static void main(String[] args) throws Exception {
        new KafkaSpoutTopologyMainNamedTopics().runMain(args);
    }

    protected void runMain(String[] args) throws Exception {
        final String brokerUrl = args.length > 0 ? args[0] : KAFKA_LOCAL_BROKER;
        System.out.println("Running with broker url: " + brokerUrl);

        Config tpConf = getConfig();
        // Producers. This is just to get some data in Kafka, normally you would be getting this data from elsewhere
        StormSubmitter.submitTopology(TOPIC_0 + "-producer", tpConf, KafkaProducerTopology.newTopology(brokerUrl, TOPIC_0));

        //Consumer. Sets up a topology that reads the given Kafka spouts and logs the received messages
        StormSubmitter.submitTopology(TOPIC_0 + "-consumer", tpConf, getTopologyKafkaSpout(getKafkaSpoutConfig(brokerUrl)));
    }

    protected Config getConfig() {
        Config config = new Config();
        config.setDebug(true);
        return config;
    }

    protected StormTopology getTopologyKafkaSpout(KafkaSpoutConfig<String, String> spoutConfig) {
        final TopologyBuilder tp = new TopologyBuilder();
        tp.setSpout("KafkaSpout", new KafkaSpout<>(getKafkaSpoutConfig(KAFKA_LOCAL_BROKER)), 1);
        tp.setBolt("SplitSentenceBolt", new SplitSentenceBolt(), 8).shuffleGrouping("KafkaSpout", TOPIC_0_STREAM);

        tp.setBolt("WordCountBolt", new WordCountBolt(), 12).fieldsGrouping("SplitSentenceBolt", new Fields("word"));
        tp.setBolt("LengthCountBolt", new LengthCountBolt(), 12).fieldsGrouping("SplitSentenceBolt", new Fields("word"));

        JedisPoolConfig poolConfig = new JedisPoolConfig.Builder().setHost("127.0.0.1").setPort(6379).build();
        RedisStoreMapper wordStoreMapper = new WordCountStoreMapper();
        RedisStoreMapper lengthStoreMapper = new LengthCountStoreMapper();
        tp.setBolt("RedisStorePairBolt", new RedisStoreBolt(poolConfig, wordStoreMapper), 12).shuffleGrouping("WordCountBolt");
        tp.setBolt("RedisStoreLengthBolt", new RedisStoreBolt(poolConfig, lengthStoreMapper), 12).shuffleGrouping("LengthCountBolt");
        return tp.createTopology();

    }

    protected KafkaSpoutConfig<String, String> getKafkaSpoutConfig(String bootstrapServers) {
        ByTopicRecordTranslator<String, String> trans = new ByTopicRecordTranslator<>(
            (r) -> new Values(r.topic(), r.partition(), r.offset(), r.key(), r.value()),
            new Fields("topic", "partition", "offset", "key", "value"), TOPIC_0_STREAM);
        return KafkaSpoutConfig.builder(bootstrapServers, new String[]{TOPIC_0})
                .setProp(ConsumerConfig.GROUP_ID_CONFIG, "kafkaSpoutGroup")
                .setRetry(getRetryService())
                .setRecordTranslator(trans)
                .setOffsetCommitPeriodMs(10_000)
                .setFirstPollOffsetStrategy(EARLIEST)
                .setMaxUncommittedOffsets(250)
                .build();
    }

    protected KafkaSpoutRetryService getRetryService() {
        return new KafkaSpoutRetryExponentialBackoff(TimeInterval.microSeconds(500),
                TimeInterval.milliSeconds(2), Integer.MAX_VALUE, TimeInterval.seconds(10));
    }

    private static class WordCountStoreMapper implements RedisStoreMapper {
        private final RedisDataTypeDescription description;
        private final String hashKey = "wordCountHashSet";

        WordCountStoreMapper() {
            description = new RedisDataTypeDescription(RedisDataTypeDescription.RedisDataType.HASH, hashKey);
        }

        @Override
        public RedisDataTypeDescription getDataTypeDescription() {
            return description;
        }

        @Override
        public String getKeyFromTuple(ITuple tuple) {
            return tuple.getStringByField("word");
        }

        @Override
        public String getValueFromTuple(ITuple tuple) {
            return String.valueOf(tuple.getIntegerByField("count"));
        }
    }

    private static class LengthCountStoreMapper implements RedisStoreMapper {
        private final RedisDataTypeDescription description;
        private final String hashKey = "lengthCountHashSet";

        LengthCountStoreMapper() {
            description = new RedisDataTypeDescription(RedisDataTypeDescription.RedisDataType.HASH, hashKey);
        }

        @Override
        public RedisDataTypeDescription getDataTypeDescription() {
            return description;
        }

        @Override
        public String getKeyFromTuple(ITuple tuple) {
            return String.valueOf(tuple.getIntegerByField("length"));
        }

        @Override
        public String getValueFromTuple(ITuple tuple) {
            return String.valueOf(tuple.getIntegerByField("count"));
        }
    }
}
