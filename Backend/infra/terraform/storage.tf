# ---------------------------------------------------------------------------
# S3: firmware binaries.
#
# PostgreSQL stores only metadata — version, checksum, size, and the S3 key.
# The binary itself lives here, and devices receive a short-lived presigned URL
# rather than a permanent public link.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "firmware" {
  bucket = "${local.name}-firmware"

  tags = { Name = "${local.name}-firmware", Purpose = "OTA firmware binaries" }
}

# Firmware is never public. A device gets a presigned URL scoped to one object
# and a few minutes; nothing else can read the bucket.
resource "aws_s3_bucket_public_access_block" "firmware" {
  bucket = aws_s3_bucket.firmware.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "firmware" {
  bucket = aws_s3_bucket.firmware.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Versioning is what makes an OTA rollback possible: the previous binary is
# still addressable after a bad release overwrites the key.
resource "aws_s3_bucket_versioning" "firmware" {
  bucket = aws_s3_bucket.firmware.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "firmware" {
  bucket = aws_s3_bucket.firmware.id

  rule {
    id     = "expire-old-firmware-versions"
    status = "Enabled"

    filter {}

    # Keep superseded binaries long enough to roll back, then reclaim storage.
    noncurrent_version_expiration {
      noncurrent_days = 180
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# Reject any upload that did not arrive over TLS.
data "aws_iam_policy_document" "firmware_bucket" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.firmware.arn, "${aws_s3_bucket.firmware.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "firmware" {
  bucket = aws_s3_bucket.firmware.id
  policy = data.aws_iam_policy_document.firmware_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.firmware]
}

# ---------------------------------------------------------------------------
# ECR: one repository per service.
# ---------------------------------------------------------------------------

locals {
  services = [
    "api-gateway",
    "auth-service",
    "device-service",
    "sensor-service",
    "analytics-service",
    "alert-service",
    "iot-ingestion-service",
    "ai-service",
  ]
}

resource "aws_ecr_repository" "services" {
  for_each = toset(local.services)

  name                 = "${var.project_name}/${each.value}"
  image_tag_mutability = "IMMUTABLE" # A deployed tag can never be swapped underneath you.

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = { Service = each.value }
}

resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = aws_ecr_repository.services
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep the last 20 images; older ones are not rollback candidates"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 20
        }
        action = { type = "expire" }
      }
    ]
  })
}
