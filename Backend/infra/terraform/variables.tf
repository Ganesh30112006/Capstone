variable "aws_region" {
  description = "AWS region for all platform resources"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment (dev, staging, prod)"
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}

variable "project_name" {
  description = "Prefix applied to every resource name"
  type        = string
  default     = "weather"
}

# ---------------------------------------------------------------------------
# Networking
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block for the platform VPC"
  type        = string
  default     = "10.20.0.0/16"
}

variable "availability_zone_count" {
  description = "Number of AZs to spread subnets across. Two is the minimum for a highly available ALB and RDS."
  type        = number
  default     = 2

  validation {
    condition     = var.availability_zone_count >= 2
    error_message = "At least two availability zones are required for ALB and RDS multi-AZ."
  }
}

# ---------------------------------------------------------------------------
# IoT
# ---------------------------------------------------------------------------

variable "iot_topic_prefix" {
  description = "Root of the MQTT topic tree. Must match aws.iot.topic-prefix in device-service."
  type        = string
  default     = "weather/devices"
}

# ---------------------------------------------------------------------------
# Messaging reliability
# ---------------------------------------------------------------------------

variable "sqs_max_receive_count" {
  description = "Deliveries attempted before a message is moved to its dead-letter queue"
  type        = number
  default     = 5
}

variable "sqs_visibility_timeout_seconds" {
  description = "How long a message stays invisible while being processed. Must exceed the ingestion service's worst-case handling time, or the message is redelivered while still in flight."
  type        = number
  default     = 60
}

variable "sqs_message_retention_seconds" {
  description = "How long an unconsumed message survives. Four days gives a weekend outage room to be noticed and drained."
  type        = number
  default     = 345600
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

variable "db_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage_gb" {
  description = "Initial RDS storage in GB"
  type        = number
  default     = 20
}

variable "db_max_allocated_storage_gb" {
  description = "Ceiling for storage autoscaling. Telemetry grows continuously, so headroom prevents a full-disk outage."
  type        = number
  default     = 200
}

variable "db_multi_az" {
  description = "Run a standby in a second AZ. Should be true in production."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Compute
# ---------------------------------------------------------------------------

variable "service_cpu" {
  description = "Fargate CPU units per service task"
  type        = number
  default     = 512
}

variable "service_memory" {
  description = "Fargate memory (MiB) per service task"
  type        = number
  default     = 1024
}

variable "log_retention_days" {
  description = "CloudWatch log retention"
  type        = number
  default     = 30
}
