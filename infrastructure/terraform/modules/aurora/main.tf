resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-aurora"
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-aurora"
  description = "Aurora PostgreSQL: 5432 from the EKS cluster only"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-aurora" })
}

resource "aws_vpc_security_group_ingress_rule" "from_eks" {
  for_each                     = toset(var.allowed_security_group_ids)
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "postgres from eks"
}

resource "aws_rds_cluster_parameter_group" "this" {
  name        = "${var.name}-aurora-pg17"
  family      = "aurora-postgresql17"
  description = "travelos ${var.name}"
  parameter {
    name  = "log_min_duration_statement"
    value = "250"
  }
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  tags = var.tags
}

resource "aws_rds_cluster" "this" {
  cluster_identifier                  = "${var.name}-aurora"
  engine                              = "aurora-postgresql"
  engine_mode                         = "provisioned"
  engine_version                      = var.engine_version
  database_name                       = "travelos"
  master_username                     = "travelos"
  manage_master_user_password         = true
  master_user_secret_kms_key_id       = var.secrets_kms_key_arn
  db_subnet_group_name                = aws_db_subnet_group.this.name
  vpc_security_group_ids              = [aws_security_group.this.id]
  db_cluster_parameter_group_name     = aws_rds_cluster_parameter_group.this.name
  storage_encrypted                   = true
  kms_key_id                          = var.kms_key_arn
  backup_retention_period             = var.backup_retention_days
  preferred_backup_window             = "03:00-04:00"
  preferred_maintenance_window        = "sun:04:30-sun:05:30"
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = !var.deletion_protection
  final_snapshot_identifier           = var.deletion_protection ? "${var.name}-aurora-final" : null
  iam_database_authentication_enabled = true
  enabled_cloudwatch_logs_exports     = ["postgresql"]

  serverlessv2_scaling_configuration {
    min_capacity = var.min_capacity
    max_capacity = var.max_capacity
  }

  tags = var.tags
}

resource "aws_rds_cluster_instance" "this" {
  count                           = var.instance_count
  identifier                      = "${var.name}-aurora-${count.index}"
  cluster_identifier              = aws_rds_cluster.this.id
  instance_class                  = "db.serverless"
  engine                          = aws_rds_cluster.this.engine
  engine_version                  = aws_rds_cluster.this.engine_version
  performance_insights_enabled    = true
  performance_insights_kms_key_id = var.kms_key_arn
  monitoring_interval             = 0
  auto_minor_version_upgrade      = true
  tags                            = var.tags
}
