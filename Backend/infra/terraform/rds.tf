# ---------------------------------------------------------------------------
# PostgreSQL.
#
# One RDS instance hosting a database per service. The database-per-service
# boundary is preserved at the schema and credential level rather than by
# running five instances, which would multiply cost without changing the
# isolation that matters: no service can read another's tables.
#
# The instance lives in the isolated subnet tier with no route to the internet.
# ---------------------------------------------------------------------------

resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db-subnets"
  subnet_ids = aws_subnet.database[*].id

  tags = { Name = "${local.name}-db-subnets" }
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name}-pg16"
  family = "postgres16"

  # Refuse unencrypted connections outright rather than relying on each client
  # to opt in.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # Surface slow queries; telemetry tables grow, and a query that was fine at
  # 10k rows is how the first outage starts.
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "random_password" "db_master" {
  length  = 32
  special = true
  # Excluded because several are awkward inside JDBC URLs and shell exports.
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_db_instance" "main" {
  identifier = "${local.name}-postgres"

  engine         = "postgres"
  engine_version = "16.4"
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage_gb
  max_allocated_storage = var.db_max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "postgres"
  username = "weatherplatform"
  password = random_password.db_master.result
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.postgres.name

  # The whole point of the isolated subnet tier.
  publicly_accessible = false

  multi_az                = var.db_multi_az
  backup_retention_period = var.environment == "prod" ? 30 : 7
  backup_window           = "03:00-04:00"
  maintenance_window       = "sun:04:00-sun:05:00"

  auto_minor_version_upgrade = true
  deletion_protection        = var.environment == "prod"
  skip_final_snapshot        = var.environment != "prod"
  final_snapshot_identifier  = var.environment == "prod" ? "${local.name}-final-snapshot" : null

  performance_insights_enabled = var.environment == "prod"
  enabled_cloudwatch_logs_exports = ["postgresql"]

  tags = { Name = "${local.name}-postgres" }
}

# ---------------------------------------------------------------------------
# Storage headroom alarm. Telemetry only ever grows, so a full disk is a
# foreseeable outage rather than a surprise.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "db_storage_low" {
  alarm_name          = "${local.name}-db-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 2147483648 # 2 GiB
  alarm_description   = "RDS free storage is low. Check the telemetry retention job is running."
  treat_missing_data  = "notBreaching"

  dimensions = { DBInstanceIdentifier = aws_db_instance.main.id }
}

resource "aws_cloudwatch_metric_alarm" "db_cpu_high" {
  alarm_name          = "${local.name}-db-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Sustained high database CPU."
  treat_missing_data  = "notBreaching"

  dimensions = { DBInstanceIdentifier = aws_db_instance.main.id }
}
