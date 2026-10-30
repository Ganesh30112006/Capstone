# ---------------------------------------------------------------------------
# Task roles.
#
# Two roles per service, deliberately:
#
#   execution role — used by the ECS agent to pull the image and read the
#                    secrets it injects. Never used by application code.
#   task role      — assumed by the running container. This is what the
#                    application's AWS SDK calls use.
#
# Each service gets only the task-role permissions it actually needs: ingestion
# can read its two queues and nothing else; device-service can manage IoT
# identities and firmware objects and nothing else.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --------------------------- Execution role ---------------------------

resource "aws_iam_role" "task_execution" {
  name               = "${local.name}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "task_execution_secrets" {
  statement {
    sid     = "ReadInjectedSecrets"
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]

    # Scoped to this deployment's secrets only.
    resources = [
      aws_secretsmanager_secret.jwt.arn,
      aws_secretsmanager_secret.gateway_internal.arn,
      aws_secretsmanager_secret.db.arn,
      aws_secretsmanager_secret.rabbitmq.arn,
      aws_secretsmanager_secret.bootstrap_admin.arn,
    ]
  }
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name   = "${local.name}-task-execution-secrets"
  role   = aws_iam_role.task_execution.id
  policy = data.aws_iam_policy_document.task_execution_secrets.json
}

# --------------------------- Default task role ---------------------------
# For services that need nothing beyond writing their own logs.

resource "aws_iam_role" "task_default" {
  name               = "${local.name}-task-default"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# --------------------------- Ingestion task role ---------------------------

resource "aws_iam_role" "task_ingestion" {
  name               = "${local.name}-task-ingestion"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

data "aws_iam_policy_document" "ingestion_permissions" {
  statement {
    sid    = "ConsumeIngestionQueues"
    effect = "Allow"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
      "sqs:ChangeMessageVisibility",
    ]
    # Read-only on exactly two queues. Notably absent: sqs:SendMessage — only
    # the IoT rules put messages here.
    resources = [aws_sqs_queue.telemetry.arn, aws_sqs_queue.heartbeat.arn]
  }
}

resource "aws_iam_role_policy" "ingestion" {
  name   = "${local.name}-ingestion-policy"
  role   = aws_iam_role.task_ingestion.id
  policy = data.aws_iam_policy_document.ingestion_permissions.json
}

# --------------------------- Device-service task role ---------------------------

resource "aws_iam_role" "task_device" {
  name               = "${local.name}-task-device"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

data "aws_iam_policy_document" "device_permissions" {
  statement {
    sid    = "ManageDeviceIdentities"
    effect = "Allow"
    actions = [
      "iot:CreateThing",
      "iot:DescribeThing",
      "iot:DeleteThing",
      "iot:CreateKeysAndCertificate",
      "iot:AttachThingPrincipal",
      "iot:DetachThingPrincipal",
      "iot:AttachPolicy",
      "iot:DetachPolicy",
      "iot:UpdateCertificate",
      "iot:DeleteCertificate",
      "iot:DescribeEndpoint",
    ]
    # Certificate creation cannot be resource-scoped by AWS, so this is as
    # narrow as the service API allows.
    resources = ["*"]
  }

  statement {
    sid       = "PublishDeviceCommands"
    effect    = "Allow"
    actions   = ["iot:Publish"]
    resources = ["arn:aws:iot:${var.aws_region}:${data.aws_caller_identity.current.account_id}:topic/${var.iot_topic_prefix}/*/ota"]
  }

  statement {
    sid    = "ManageDeviceShadows"
    effect = "Allow"
    actions = [
      "iot:GetThingShadow",
      "iot:UpdateThingShadow",
    ]
    resources = ["arn:aws:iot:${var.aws_region}:${data.aws_caller_identity.current.account_id}:thing/*"]
  }

  statement {
    sid    = "ManageFirmwareObjects"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]
    resources = ["${aws_s3_bucket.firmware.arn}/*"]
  }

  statement {
    sid       = "ListFirmwareBucket"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.firmware.arn]
  }
}

resource "aws_iam_role_policy" "device" {
  name   = "${local.name}-device-policy"
  role   = aws_iam_role.task_device.id
  policy = data.aws_iam_policy_document.device_permissions.json
}

# --------------------------- CloudWatch for all task roles ---------------------------

data "aws_iam_policy_document" "task_logging" {
  statement {
    sid    = "WriteOwnLogs"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.services.arn}:*"]
  }

  statement {
    sid       = "PublishCustomMetrics"
    effect    = "Allow"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["WeatherPlatform"]
    }
  }
}

resource "aws_iam_role_policy" "task_logging" {
  for_each = toset([
    aws_iam_role.task_default.id,
    aws_iam_role.task_ingestion.id,
    aws_iam_role.task_device.id,
  ])

  name   = "${local.name}-task-logging"
  role   = each.value
  policy = data.aws_iam_policy_document.task_logging.json
}
