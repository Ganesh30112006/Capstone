# ---------------------------------------------------------------------------
# Amazon MQ for RabbitMQ — the internal event bus.
#
# Managed rather than self-hosted: the platform's reliability story depends on
# the broker not losing messages, and running RabbitMQ on a single container
# with no persistence would undo the DLQ and retry work in the services.
#
# The broker is private-only; nothing outside the VPC can reach it.
# ---------------------------------------------------------------------------

resource "aws_security_group" "broker" {
  name        = "${local.name}-broker-sg"
  description = "RabbitMQ broker. Reachable only from the service tier."
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "AMQPS from services"
    from_port       = 5671
    to_port         = 5671
    protocol        = "tcp"
    security_groups = [aws_security_group.services.id]
  }

  tags = { Name = "${local.name}-broker-sg" }
}

resource "aws_mq_broker" "rabbitmq" {
  broker_name = "${local.name}-rabbitmq"

  engine_type    = "RabbitMQ"
  engine_version = "3.13"
  host_instance_type = var.environment == "prod" ? "mq.m5.large" : "mq.t3.micro"

  # A single-instance broker has a restart window during maintenance; a cluster
  # does not. Production takes the cluster.
  deployment_mode = var.environment == "prod" ? "CLUSTER_MULTI_AZ" : "SINGLE_INSTANCE"

  # SINGLE_INSTANCE takes exactly one subnet; the cluster spreads across AZs.
  subnet_ids = var.environment == "prod" ? aws_subnet.private[*].id : [aws_subnet.private[0].id]

  security_groups    = [aws_security_group.broker.id]
  publicly_accessible = false

  encryption_options {
    use_aws_owned_key = true
  }

  user {
    username = jsondecode(aws_secretsmanager_secret_version.rabbitmq.secret_string)["username"]
    password = jsondecode(aws_secretsmanager_secret_version.rabbitmq.secret_string)["password"]
  }

  maintenance_window_start_time {
    day_of_week = "SUNDAY"
    time_of_day = "05:00"
    time_zone   = "UTC"
  }

  auto_minor_version_upgrade = true

  logs {
    general = true
  }

  tags = { Name = "${local.name}-rabbitmq" }
}
