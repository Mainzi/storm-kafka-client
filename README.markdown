```
./bin/zkServer.sh start

./bin/storm nimbus
./bin/storm supervisor
./bin/storm ui

bin/kafka-server-start.sh config/server.properties

mvn clean package -Dstorm.kafka.client.version=2.8.0
../../bin/storm jar target/storm-kafka-client-examples-2.2.0.jar org.apache.storm.kafka.spout.KafkaSpoutTopologyMainNamedTopics


bin/kafka-console-producer.sh --topic random-sentence --bootstrap-server localhost:9092

redis-cli
hgetall wordCountHashSet
hgetall lengthCountHashSet
```

