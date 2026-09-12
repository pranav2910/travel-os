locals {
  # /16 -> /20 per subnet tier per AZ: public 0-2, private 3-5, database 6-8.
  public_cidrs   = [for i in range(3) : cidrsubnet(var.cidr, 4, i)]
  private_cidrs  = [for i in range(3) : cidrsubnet(var.cidr, 4, i + 3)]
  database_cidrs = [for i in range(3) : cidrsubnet(var.cidr, 4, i + 6)]
  nat_count      = var.single_nat_gateway ? 1 : 3
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = var.name })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = var.name })
}

resource "aws_subnet" "public" {
  count                   = 3
  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.public_cidrs[count.index]
  availability_zone       = var.azs[count.index]
  map_public_ip_on_launch = false
  tags = merge(var.tags, {
    Name                     = "${var.name}-public-${var.azs[count.index]}"
    "kubernetes.io/role/elb" = "1"
    tier                     = "public"
  })
}

resource "aws_subnet" "private" {
  count             = 3
  vpc_id            = aws_vpc.this.id
  cidr_block        = local.private_cidrs[count.index]
  availability_zone = var.azs[count.index]
  tags = merge(var.tags, {
    Name                              = "${var.name}-private-${var.azs[count.index]}"
    "kubernetes.io/role/internal-elb" = "1"
    tier                              = "private"
  })
}

resource "aws_subnet" "database" {
  count             = 3
  vpc_id            = aws_vpc.this.id
  cidr_block        = local.database_cidrs[count.index]
  availability_zone = var.azs[count.index]
  tags              = merge(var.tags, { Name = "${var.name}-database-${var.azs[count.index]}", tier = "database" })
}

resource "aws_eip" "nat" {
  count  = local.nat_count
  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name}-nat-${count.index}" })
}

resource "aws_nat_gateway" "this" {
  count         = local.nat_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = merge(var.tags, { Name = "${var.name}-nat-${var.azs[count.index]}" })
  depends_on    = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = 3
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  count  = 3
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-private-${var.azs[count.index]}" })
}

resource "aws_route" "private_nat" {
  count                  = 3
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.single_nat_gateway ? 0 : count.index].id
}

resource "aws_route_table_association" "private" {
  count          = 3
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# Database subnets have no route to the internet at all.
resource "aws_route_table" "database" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-database" })
}

resource "aws_route_table_association" "database" {
  count          = 3
  subnet_id      = aws_subnet.database[count.index].id
  route_table_id = aws_route_table.database.id
}

# S3 stays on the AWS backbone (image layers, audit archives) instead of the NAT.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = concat([aws_route_table.public.id, aws_route_table.database.id], aws_route_table.private[*].id)
  tags              = merge(var.tags, { Name = "${var.name}-s3" })
}

data "aws_region" "current" {}

resource "aws_cloudwatch_log_group" "flow" {
  name              = "/travelos/${var.name}/vpc-flow-logs"
  kms_key_id        = var.logs_kms_key_arn
  retention_in_days = var.flow_logs_retention_days
  tags              = var.tags
}

data "aws_iam_policy_document" "flow_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow" {
  name               = "${var.name}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "flow" {
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogGroups", "logs:DescribeLogStreams"]
    resources = ["${aws_cloudwatch_log_group.flow.arn}:*"]
  }
}

resource "aws_iam_role_policy" "flow" {
  role   = aws_iam_role.flow.id
  policy = data.aws_iam_policy_document.flow.json
}

resource "aws_flow_log" "this" {
  vpc_id          = aws_vpc.this.id
  traffic_type    = "ALL"
  iam_role_arn    = aws_iam_role.flow.arn
  log_destination = aws_cloudwatch_log_group.flow.arn
  tags            = var.tags
}
