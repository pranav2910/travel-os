data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  # service account -> namespace. Each role trusts exactly one service account.
  kafka_producers = ["travel-core", "policy", "order", "optimization", "supplier-gateway", "disruption"]
  kafka_consumers = ["audit", "trip-planning", "disruption"]
  bindings = merge(
    {
      external-secrets             = "external-secrets"
      aws-load-balancer-controller = "kube-system"
    },
    { for s in distinct(concat(local.kafka_producers, local.kafka_consumers)) : s => "travelos" },
  )
  # arn:aws:kafka:<region>:<account>:cluster/<name>/<uuid> -> topic/<name>/<uuid>/<topic>, group/<name>/<uuid>/<group>
  msk_topic_prefix = replace(var.msk_cluster_arn, ":cluster/", ":topic/")
  msk_group_prefix = replace(var.msk_cluster_arn, ":cluster/", ":group/")
}

data "aws_iam_policy_document" "assume" {
  for_each = local.bindings
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${each.value}:${each.key}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  for_each           = local.bindings
  name               = "${var.name}-irsa-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.assume[each.key].json
  tags               = var.tags
}

# External Secrets Operator: read only travelos/<env>/* and decrypt with the secrets key.
data "aws_iam_policy_document" "external_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = ["arn:aws:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:${var.secret_name_prefix}*"]
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [var.secrets_kms_key_arn]
  }
}

resource "aws_iam_role_policy" "external_secrets" {
  role   = aws_iam_role.this["external-secrets"].id
  policy = data.aws_iam_policy_document.external_secrets.json
}

# Audit service: append-only archive to its bucket, nothing else.
data "aws_iam_policy_document" "audit" {
  statement {
    actions   = ["s3:PutObject"]
    resources = ["${var.audit_bucket_arn}/*"]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [var.audit_bucket_arn]
  }
}

resource "aws_iam_role_policy" "audit" {
  role   = aws_iam_role.this["audit"].id
  policy = data.aws_iam_policy_document.audit.json
}

# Kafka on MSK with SASL/IAM: producers may write travel.* topics, consumers may read them and own
# their consumer group. Nothing may create topics (the platform declares them) or touch other topics.
data "aws_iam_policy_document" "kafka_producer" {
  statement {
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
    resources = [var.msk_cluster_arn]
  }
  statement {
    actions   = ["kafka-cluster:DescribeTopic", "kafka-cluster:WriteData"]
    resources = ["${local.msk_topic_prefix}/travel.*"]
  }
}

data "aws_iam_policy_document" "kafka_consumer" {
  statement {
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
    resources = [var.msk_cluster_arn]
  }
  statement {
    actions   = ["kafka-cluster:DescribeTopic", "kafka-cluster:ReadData"]
    resources = ["${local.msk_topic_prefix}/travel.*"]
  }
  statement {
    actions   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
    resources = ["${local.msk_group_prefix}/*"]
  }
}

resource "aws_iam_role_policy" "kafka_producer" {
  for_each = toset(local.kafka_producers)
  name     = "kafka-producer"
  role     = aws_iam_role.this[each.key].id
  policy   = data.aws_iam_policy_document.kafka_producer.json
}

resource "aws_iam_role_policy" "kafka_consumer" {
  for_each = toset(local.kafka_consumers)
  name     = "kafka-consumer"
  role     = aws_iam_role.this[each.key].id
  policy   = data.aws_iam_policy_document.kafka_consumer.json
}

# AWS Load Balancer Controller: the upstream policy, vendored at the controller version we install.
resource "aws_iam_role_policy" "aws_load_balancer_controller" {
  name   = "aws-load-balancer-controller"
  role   = aws_iam_role.this["aws-load-balancer-controller"].id
  policy = file("${path.module}/policies/aws-load-balancer-controller.json")
}
