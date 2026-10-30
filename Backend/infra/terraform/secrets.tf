# ---------------------------------------------------------------------------
# Secrets Manager.
#
# Nothing sensitive is ever passed to a task as a plain environment variable in
# the task definition — those are visible to anyone who can describe the task.
# Instead every secret is stored here and injected by ECS at container start
# via the `secrets` block, so the value never appears in Terraform state output,
# the console, or `aws ecs describe-task-definition`.
# ---------------------------------------------------------------------------

# The signing key for platform JWTs. Shared by auth-service (which signs) and
# the gateway (which verifies).
resource "random_password" "jwt_secret" {
  length  = 64
  special = false # Base64-safe: it is used directly as HMAC key bytes.
}

resource "aws_secretsmanager_secret" "jwt" {
  name                    = "${local.name}/jwt-secret"
  description             = "HMAC signing key for platform JWTs"
  recovery_window_in_days = var.environment == "prod" ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_password.jwt_secret.result
}

# Proves a request was proxied by the gateway. Defence in depth behind private
# networking: a request that somehow reaches a service port directly is rejected.
resource "random_password" "gateway_internal_secret" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "gateway_internal" {
  name                    = "${local.name}/gateway-internal-secret"
  description             = "Shared secret proving gateway origin for internal calls"
  recovery_window_in_days = var.environment == "prod" ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "gateway_internal" {
  secret_id     = aws_secretsmanager_secret.gateway_internal.id
  secret_string = random_password.gateway_internal_secret.result
}

# Database credentials, stored as JSON so a future rotation Lambda can consume
# them in the standard RDS format.
resource "aws_secretsmanager_secret" "db" {
  name                    = "${local.name}/database"
  description             = "PostgreSQL master credentials"
  recovery_window_in_days = var.environment == "prod" ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = aws_db_instance.main.username
    password = random_password.db_master.result
    host     = aws_db_instance.main.address
    port     = aws_db_instance.main.port
    engine   = "postgres"
  })
}

# RabbitMQ (Amazon MQ) credentials.
resource "random_password" "rabbitmq" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+"
}

resource "aws_secretsmanager_secret" "rabbitmq" {
  name                    = "${local.name}/rabbitmq"
  description             = "RabbitMQ broker credentials"
  recovery_window_in_days = var.environment == "prod" ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "rabbitmq" {
  secret_id = aws_secretsmanager_secret.rabbitmq.id
  secret_string = jsonencode({
    username = "weatherplatform"
    password = random_password.rabbitmq.result
  })
}

# ---------------------------------------------------------------------------
# Bootstrap administrator.
#
# Self-registration only ever grants USER, so the first ADMIN has to come from
# somewhere. Generating it here means no default credential is ever committed
# to the repository or baked into an image. Retrieve it once, sign in, change
# the password, then delete this secret.
# ---------------------------------------------------------------------------

resource "random_password" "bootstrap_admin" {
  length           = 24
  special          = true
  override_special = "!#$%&*-_=+"
}

resource "aws_secretsmanager_secret" "bootstrap_admin" {
  name                    = "${local.name}/bootstrap-admin"
  description             = "One-time administrator credentials. Rotate after first sign-in."
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "bootstrap_admin" {
  secret_id = aws_secretsmanager_secret.bootstrap_admin.id
  secret_string = jsonencode({
    email    = "admin@${var.project_name}-${var.environment}.local"
    password = random_password.bootstrap_admin.result
  })
}
