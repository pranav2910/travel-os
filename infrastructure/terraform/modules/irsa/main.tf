data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  # service account -> namespace. Each role trusts exactly one service account.
  bindings = {
    external-secrets = "external-secrets"
    audit            = "travelos"
  }
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
