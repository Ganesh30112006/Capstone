# ---------------------------------------------------------------------------
# Network topology.
#
#   Internet → ALB (public subnets) → Fargate services (private subnets)
#                                          ↓
#                                    RDS (isolated subnets)
#
# Only the ALB is reachable from the internet. The microservices have no public
# IPs and the database has no route to an internet gateway at all, so
# "internal services are not publicly exposed" is enforced by routing rather
# than by configuration anyone can get wrong.
# ---------------------------------------------------------------------------

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "${var.project_name}-${var.environment}"
  azs  = slice(data.aws_availability_zones.available.names, 0, var.availability_zone_count)
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name}-vpc" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-igw" }
}

# --------------------------- Subnets ---------------------------

resource "aws_subnet" "public" {
  count = var.availability_zone_count

  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${local.azs[count.index]}", Tier = "public" }
}

resource "aws_subnet" "private" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.name}-private-${local.azs[count.index]}", Tier = "application" }
}

resource "aws_subnet" "database" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 20)
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.name}-db-${local.azs[count.index]}", Tier = "database" }
}

# --------------------------- Routing ---------------------------

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public-rt" }
}

resource "aws_route_table_association" "public" {
  count          = var.availability_zone_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# NAT lets private services reach AWS APIs and pull images. One per AZ in
# production so a single AZ failure cannot cut off the others.
resource "aws_eip" "nat" {
  count  = var.environment == "prod" ? var.availability_zone_count : 1
  domain = "vpc"
  tags   = { Name = "${local.name}-nat-eip-${count.index}" }
}

resource "aws_nat_gateway" "main" {
  count = var.environment == "prod" ? var.availability_zone_count : 1

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags       = { Name = "${local.name}-nat-${count.index}" }
  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "private" {
  count  = var.availability_zone_count
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[var.environment == "prod" ? count.index : 0].id
  }

  tags = { Name = "${local.name}-private-rt-${count.index}" }
}

resource "aws_route_table_association" "private" {
  count          = var.availability_zone_count
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# Database subnets get no default route at all — the tier is unreachable from
# and cannot reach the internet, by construction.
resource "aws_route_table" "database" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-db-rt" }
}

resource "aws_route_table_association" "database" {
  count          = var.availability_zone_count
  subnet_id      = aws_subnet.database[count.index].id
  route_table_id = aws_route_table.database.id
}

# --------------------------- Security groups ---------------------------

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb-sg"
  description = "Public entry point. HTTPS only."
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTPS from the internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP, redirected to HTTPS at the listener"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "To the gateway task only"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr]
  }

  tags = { Name = "${local.name}-alb-sg" }
}

resource "aws_security_group" "gateway" {
  name        = "${local.name}-gateway-sg"
  description = "API gateway task. Reachable only from the ALB."
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "From the ALB"
    from_port       = 8443
    to_port         = 8443
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name}-gateway-sg" }
}

resource "aws_security_group" "services" {
  name        = "${local.name}-services-sg"
  description = "Internal microservices. Reachable only from the gateway and each other."
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "From the API gateway"
    from_port       = 8000
    to_port         = 8086
    protocol        = "tcp"
    security_groups = [aws_security_group.gateway.id]
  }

  ingress {
    description = "Between services (health probes, internal calls)"
    from_port   = 8000
    to_port     = 8086
    protocol    = "tcp"
    self        = true
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name}-services-sg" }
}

resource "aws_security_group" "database" {
  name        = "${local.name}-db-sg"
  description = "PostgreSQL. Reachable only from the service tier."
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from services"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.services.id]
  }

  # No egress rule: the database never initiates outbound connections.

  tags = { Name = "${local.name}-db-sg" }
}
