# ---------------------------------------------------------------------------
# SQS: the buffer between AWS IoT Core and the ingestion service.
#
# IoT Core delivers at whatever rate the fleet produces; the ingestion service
# consumes at whatever rate it can sustain. The queue absorbs the difference, so
# a burst of telemetry or a slow consumer never loses a reading.
#
# Every queue has a dead-letter queue with an explicit redrive policy. Without
# one, a poison message is retried forever and blocks the queue behind it.
# ---------------------------------------------------------------------------

locals {
  queue_prefix = "${var.project_name}-${var.environment}"
}

# --------------------------- Telemetry ---------------------------

resource "aws_sqs_queue" "telemetry_dlq" {
  name = "${local.queue_prefix}-iot-telemetry-dlq"

  # DLQ contents are evidence for debugging, so they outlive the main queue's
  # retention — a poison message found on Monday is still there to inspect.
  message_retention_seconds = 1209600 # 14 days
  sqs_managed_sse_enabled   = true

  tags = { Purpose = "Telemetry messages that exhausted all delivery attempts" }
}

resource "aws_sqs_queue" "telemetry" {
  name = "${local.queue_prefix}-iot-telemetry"

  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds
  message_retention_seconds  = var.sqs_message_retention_seconds

  # Long polling: fewer empty receives, lower cost, lower latency than the
  # short-poll default.
  receive_wait_time_seconds = 20

  sqs_managed_sse_enabled = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.telemetry_dlq.arn
    maxReceiveCount     = var.sqs_max_receive_count
  })

  tags = { Purpose = "Environmental telemetry from the ESP32 fleet" }
}

# --------------------------- Heartbeat ---------------------------

resource "aws_sqs_queue" "heartbeat_dlq" {
  name                      = "${local.queue_prefix}-iot-heartbeat-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = { Purpose = "Heartbeats that exhausted all delivery attempts" }
}

resource "aws_sqs_queue" "heartbeat" {
  name = "${local.queue_prefix}-iot-heartbeat"

  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds
  message_retention_seconds  = var.sqs_message_retention_seconds
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.heartbeat_dlq.arn
    maxReceiveCount     = var.sqs_max_receive_count
  })

  tags = { Purpose = "Device liveness, battery, Wi-Fi and firmware reports" }
}

# ---------------------------------------------------------------------------
# Queue policies: only the IoT rules may publish. Devices reach SQS through
# IoT Core, never directly.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "telemetry_queue_policy" {
  statement {
    sid    = "AllowIoTRuleToSendTelemetry"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["iot.amazonaws.com"]
    }

    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.telemetry.arn]

    # Confused-deputy guard: only rules in this account may publish here.
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_iot_topic_rule.telemetry_to_sqs.arn]
    }
  }
}

resource "aws_sqs_queue_policy" "telemetry" {
  queue_url = aws_sqs_queue.telemetry.id
  policy    = data.aws_iam_policy_document.telemetry_queue_policy.json
}

data "aws_iam_policy_document" "heartbeat_queue_policy" {
  statement {
    sid    = "AllowIoTRuleToSendHeartbeat"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["iot.amazonaws.com"]
    }

    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.heartbeat.arn]

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_iot_topic_rule.heartbeat_to_sqs.arn]
    }
  }
}

resource "aws_sqs_queue_policy" "heartbeat" {
  queue_url = aws_sqs_queue.heartbeat.id
  policy    = data.aws_iam_policy_document.heartbeat_queue_policy.json
}

# ---------------------------------------------------------------------------
# Alarms: a growing backlog or a filling DLQ are the two signals that the
# telemetry pipeline is in trouble.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "telemetry_backlog" {
  alarm_name          = "${local.queue_prefix}-telemetry-backlog"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Average"
  threshold           = 1000
  alarm_description   = "Ingestion is falling behind the fleet; telemetry is queueing up."
  treat_missing_data  = "notBreaching"

  dimensions = { QueueName = aws_sqs_queue.telemetry.name }
}

resource "aws_cloudwatch_metric_alarm" "telemetry_dlq_not_empty" {
  alarm_name          = "${local.queue_prefix}-telemetry-dlq-not-empty"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  alarm_description   = "Telemetry has been dead-lettered. Any message here is data the platform failed to process."
  treat_missing_data  = "notBreaching"

  dimensions = { QueueName = aws_sqs_queue.telemetry_dlq.name }
}

resource "aws_cloudwatch_metric_alarm" "heartbeat_dlq_not_empty" {
  alarm_name          = "${local.queue_prefix}-heartbeat-dlq-not-empty"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  alarm_description   = "Heartbeats have been dead-lettered; device liveness may be stale."
  treat_missing_data  = "notBreaching"

  dimensions = { QueueName = aws_sqs_queue.heartbeat_dlq.name }
}
