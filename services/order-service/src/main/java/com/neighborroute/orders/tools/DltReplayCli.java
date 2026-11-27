
package com.neighborroute.orders.tools;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class DltReplayCli {

    private static final String SOURCE_TOPIC =
            "batches.events.v1.DLT";

    private static final String TARGET_TOPIC =
            "batches.events.v1";

    private DltReplayCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException(
                    "Использование: <bootstrapServers> <partition> " +
                            "<offset> <eventId> [--publish]"
            );
        }

        String bootstrapServers = args[0];
        int partition = Integer.parseInt(args[1]);
        long offset = Long.parseLong(args[2]);
        UUID expectedEventId = UUID.fromString(args[3]);

        boolean publish = args.length == 5;

        if (publish && !"--publish".equals(args[4])) {
            throw new IllegalArgumentException(
                    "Unknown argument: " + args[4]
            );
        }

        if (partition < 0 || offset < 0) {
            throw new IllegalArgumentException(
                    "Partition and offset must be non-negative"
            );
        }

        ConsumerRecord<String, String> failedRecord =
                readExactRecord(
                        bootstrapServers,
                        partition,
                        offset
                );


        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode json = mapper.readTree(failedRecord.value());

        if (!"BatchDelivered".equals(
                json.path("eventType").asText())) {
            throw new IllegalStateException(
                    "Unexpected event type"
            );
        }

        String actualEventId =
                json.path("eventId").asText();

        if (!expectedEventId.toString().equals(actualEventId)) {
            throw new IllegalStateException(
                    "Event ID mismatch: " + actualEventId
            );
        }

        UUID batchId = UUID.fromString(
                json.path("batchId").asText()
        );

        if (!batchId.toString().equals(failedRecord.key())) {
            throw new IllegalStateException(
                    "Kafka key does not match batchId"
            );
        }

        if (!json.path("orderIds").isArray()) {
            throw new IllegalStateException(
                    "Invalid orderIds"
            );
        }

        System.out.println("Source: " + SOURCE_TOPIC);
        System.out.println("Partition: " + partition);
        System.out.println("Offset: " + offset);
        System.out.println("Event ID: " + actualEventId);
        System.out.println("Batch ID: " + batchId);
        System.out.println("Payload: " + failedRecord.value());

        if (!publish) {
            System.out.println(
                    "DRY RUN. Nothing was published."
            );
            return;
        }

        Properties properties = new Properties();

        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );
        properties.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class
        );
        properties.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class
        );
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true
        );

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(properties)) {

            ProducerRecord<String, String> newRecord =
                    new ProducerRecord<>(
                            TARGET_TOPIC,
                            failedRecord.key(),
                            failedRecord.value()
                    );

            RecordMetadata metadata = producer.send(newRecord)
                    .get(10, TimeUnit.SECONDS);

            System.out.println(
                    "Published to " + metadata.topic() +
                            ", partition=" + metadata.partition() +
                            ", offset=" + metadata.offset()
            );
        }
    }

    private static ConsumerRecord<String, String> readExactRecord(
            String bootstrapServers,
            int partition,
            long offset
    ) {
        Properties properties = new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );
        properties.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );
        properties.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );
        properties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );
        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "none"
        );

        TopicPartition topicPartition =
                new TopicPartition(SOURCE_TOPIC, partition);

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(properties)) {

            consumer.assign(List.of(topicPartition));
            consumer.seek(topicPartition, offset);

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(10);

            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record :
                        records.records(topicPartition)) {

                    if (record.offset() == offset) {
                        return record;
                    }

                    if (record.offset() > offset) {
                        throw new IllegalStateException(
                                "Requested offset no longer exists"
                        );
                    }
                }
            }
        }

        throw new IllegalStateException(
                "DLT record not found at offset " + offset
        );
    }
}
