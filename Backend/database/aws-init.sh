#!/bin/bash
set -euo pipefail

echo "Initializing LocalStack AWS Services (SQS) for IoT Ingestion..."

# The dead-letter queues are created first because the main queues reference
# their ARNs in a redrive policy.
awslocal sqs create-queue --queue-name iot-telemetry-dlq  > /dev/null
awslocal sqs create-queue --queue-name iot-heartbeat-dlq  > /dev/null

awslocal sqs create-queue --queue-name iot-telemetry-queue  > /dev/null
awslocal sqs create-queue --queue-name iot-heartbeat-queue  > /dev/null

# Attach the redrive policy.
#
# SqsMessageProcessor deliberately rethrows on failure so the message returns to
# SQS instead of being acknowledged, and its contract says a payload it cannot
# use ends up in the dead-letter queue "rather than being silently discarded".
# That contract did not hold: both dead-letter queues existed but neither was
# ever named as a redrive target, so nothing could reach them. A structurally
# invalid payload was redelivered every thirty seconds, logged an error each
# time, and was dropped four days later when SQS retention expired -- never
# appearing in the queue meant to hold exactly that.
#
# maxReceiveCount is 5. At a thirty-second visibility timeout that is around two
# and a half minutes of redelivery, which rides out a broker restart without
# shunting healthy telemetry aside, while still ending the retry loop for a
# message that can never succeed. A backlog that does exceed it lands in the
# dead-letter queue intact and can be replayed; it is not lost.
redrive() {
  local queue="$1" dlq="$2"
  local arn
  arn=$(awslocal sqs get-queue-attributes \
          --queue-url "$(awslocal sqs get-queue-url --queue-name "$dlq" --query QueueUrl --output text)" \
          --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
  awslocal sqs set-queue-attributes \
    --queue-url "$(awslocal sqs get-queue-url --queue-name "$queue" --query QueueUrl --output text)" \
    --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}"
  echo "  $queue -> $dlq (maxReceiveCount=5)"
}

redrive iot-telemetry-queue iot-telemetry-dlq
redrive iot-heartbeat-queue iot-heartbeat-dlq

echo "LocalStack SQS initialization complete!"
