resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-redis"
  description = "Redis: 6379 from the EKS cluster only"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-redis" })
}

resource "aws_vpc_security_group_ingress_rule" "from_eks" {
  for_each                     = toset(var.allowed_security_group_ids)
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  description                  = "redis from eks"
}

resource "random_password" "auth" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "auth" {
  name       = "travelos/${var.name}/redis/auth"
  kms_key_id = var.secrets_kms_key_arn
  tags       = var.tags
}

resource "aws_secretsmanager_secret_version" "auth" {
  secret_id     = aws_secretsmanager_secret.auth.id
  secret_string = jsonencode({ auth_token = random_password.auth.result })
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = "${var.name}-redis"
  description                = "travelos ${var.name} cache and rate limits"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.node_type
  num_cache_clusters         = var.num_cache_clusters
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.this.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn
  auth_token                 = random_password.auth.result
  automatic_failover_enabled = var.num_cache_clusters > 1
  multi_az_enabled           = var.num_cache_clusters > 1
  apply_immediately          = false
  tags                       = var.tags
}
