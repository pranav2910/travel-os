data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

resource "aws_kms_key" "this" {
  for_each                = toset(var.purposes)
  description             = "travelos ${var.name} ${each.key}"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = merge(var.tags, { purpose = each.key })
}

resource "aws_kms_alias" "this" {
  for_each      = aws_kms_key.this
  name          = "alias/travelos-${var.name}-${each.key}"
  target_key_id = each.value.key_id
}

# CloudWatch Logs encrypts log groups with this key, so its service principal must be allowed to use it.
data "aws_iam_policy_document" "logs" {
  statement {
    sid       = "AccountAdmin"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
  statement {
    sid       = "CloudWatchLogs"
    actions   = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["logs.${data.aws_region.current.region}.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:*"]
    }
  }
}

resource "aws_kms_key" "logs" {
  description             = "travelos ${var.name} cloudwatch logs"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.logs.json
  tags                    = merge(var.tags, { purpose = "logs" })
}

resource "aws_kms_alias" "logs" {
  name          = "alias/travelos-${var.name}-logs"
  target_key_id = aws_kms_key.logs.key_id
}
