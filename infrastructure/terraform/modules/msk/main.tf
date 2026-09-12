resource "aws_security_group" "this" {
  name        = "${var.name}-msk"
  description = "MSK brokers: 9098 (SASL/IAM over TLS) from the EKS cluster only"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-msk" })
}

resource "aws_vpc_security_group_ingress_rule" "from_eks" {
  for_each                     = toset(var.allowed_security_group_ids)
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = 9098
  to_port                      = 9098
  ip_protocol                  = "tcp"
  description                  = "kafka sasl iam from eks"
}

resource "aws_cloudwatch_log_group" "broker" {
  name              = "/travelos/${var.name}/msk-broker"
  kms_key_id        = var.logs_kms_key_arn
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

# The platform declares topics; brokers never invent them.
resource "aws_msk_configuration" "this" {
  name              = "${var.name}-msk"
  kafka_versions    = [var.kafka_version]
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=12
    log.retention.hours=168
  PROPERTIES
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name}-msk"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = 3
  enhanced_monitoring    = "PER_TOPIC_PER_BROKER"

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.volume_size
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.kms_key_arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    sasl {
      iam = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker.name
      }
    }
  }

  tags = var.tags
}
