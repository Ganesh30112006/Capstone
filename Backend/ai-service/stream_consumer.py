"""The AI worker.

Consumes ``sensor.reading.created`` — the backend branch — and turns each
reading into anomaly, forecast and report events on ``weather.events``, which
iot-stream-service relays to the dashboard.

    AWS IoT Core --> ingestion --> iot.telemetry.received
                                       |
                                       |-- iot.stream.source.telemetry --> broadcast to the dashboard
                                       |
                                       `-- sensor.telemetry.inbound --> sensor-service (persists)
                                                                          |
                                                                          `-- sensor.reading.created --> here,
                                                                              analytics, alerts, digital twin

There was a second worker scoring the broadcast branch as well, so every reading
was scored twice — two Isolation Forest passes and two Chronos passes for one
measurement. The branches are not redundant copies of each other: one exists to
show readings, this one exists to reason about them, and the reasoning belongs
on the branch that also feeds analytics, alerting and the digital twin.

It is off the storage path: if this thread is slow or down, readings are still
persisted and deterministic alerts still fire. What stops is scoring.
"""

import json
import logging
import threading
import time

import pika

import config
import metrics
from broker import connection_parameters
from security import scrub
from stream_processor import stream_processor

logger = logging.getLogger(__name__)

QUEUE_NAME = "ai.processing.queue"
DLQ_NAME = "ai.processing.queue.dlq"
DLX_NAME = "weather.events.dlx"
SOURCE_ROUTING_KEY = "sensor.reading.created"


class StreamConsumerThread(threading.Thread):

    def __init__(self):
        super().__init__(daemon=True, name="ai-stream-consumer")
        self._running = threading.Event()
        self._running.set()
        self.connected = False

    def stop(self) -> None:
        self._running.clear()

    def _publish(self, channel, routing_key: str, payload: dict) -> None:
        channel.basic_publish(
            exchange=config.RABBITMQ_EXCHANGE,
            routing_key=routing_key,
            body=json.dumps(payload),
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=pika.DeliveryMode.Persistent,
                message_id=payload.get("eventId"),
            ),
        )

    def _handle_message(self, channel, method, _properties, body) -> None:
        # Size is checked before parsing: json.loads builds the whole structure
        # before anything downstream gets to reject it.
        if len(body) > config.MAX_MESSAGE_BYTES:
            logger.error("Discarding a %d byte stream message (limit %d) to the DLQ",
                         len(body), config.MAX_MESSAGE_BYTES)
            metrics.INVALID_READINGS.labels("oversized").inc()
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            return

        try:
            message = json.loads(body)
        except json.JSONDecodeError:
            logger.error("Discarding unparseable stream message to the DLQ")
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            return

        if not isinstance(message, dict):
            logger.error("Discarding a non-object stream message to the DLQ")
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            return

        try:
            events = stream_processor.handle_reading(message)
            for routing_key, payload in events:
                self._publish(channel, routing_key, payload)
                logger.info("Published %s for %s", routing_key, scrub(payload.get("deviceUid")))
            channel.basic_ack(delivery_tag=method.delivery_tag)
        except Exception:
            # Never let one bad reading take the worker down. The identifier is
            # scrubbed: it arrived over the network, and a newline in it would
            # forge a log line.
            logger.exception("Stream processing failed for %s; routing to the DLQ",
                             scrub(message.get("deviceUid")))
            channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)

    def _declare_topology(self, channel) -> None:
        channel.exchange_declare(exchange=config.RABBITMQ_EXCHANGE, exchange_type="topic", durable=True)
        channel.exchange_declare(exchange=DLX_NAME, exchange_type="topic", durable=True)
        channel.queue_declare(
            queue=QUEUE_NAME,
            durable=True,
            arguments={
                "x-dead-letter-exchange": DLX_NAME,
                "x-dead-letter-routing-key": DLQ_NAME,
            },
        )
        channel.queue_declare(queue=DLQ_NAME, durable=True)
        channel.queue_bind(exchange=config.RABBITMQ_EXCHANGE, queue=QUEUE_NAME, routing_key=SOURCE_ROUTING_KEY)
        channel.queue_bind(exchange=DLX_NAME, queue=DLQ_NAME, routing_key=DLQ_NAME)

    def run(self) -> None:
        logger.info("AI stream consumer starting")
        backoff = 1
        while self._running.is_set():
            connection = None
            try:
                connection = pika.BlockingConnection(connection_parameters())
                channel = connection.channel()
                self._declare_topology(channel)
                channel.basic_qos(prefetch_count=1)
                channel.basic_consume(queue=QUEUE_NAME, on_message_callback=self._handle_message)

                self.connected = True
                backoff = 1
                logger.info("AI stream worker bound to %s", QUEUE_NAME)
                channel.start_consuming()

            except pika.exceptions.AMQPConnectionError:
                self.connected = False
                logger.warning("RabbitMQ unavailable for stream worker; retrying in %ss", backoff)
            except Exception:
                self.connected = False
                logger.exception("AI stream worker error; retrying in %ss", backoff)
            finally:
                self.connected = False
                if connection is not None and connection.is_open:
                    try:
                        connection.close()
                    except Exception:
                        pass

            if self._running.is_set():
                time.sleep(backoff)
                backoff = min(backoff * 2, 30)


consumer = StreamConsumerThread()


def start_consumer() -> None:
    if not consumer.is_alive():
        consumer.start()


def stop_consumer() -> None:
    consumer.stop()


def is_connected() -> bool:
    return consumer.connected
