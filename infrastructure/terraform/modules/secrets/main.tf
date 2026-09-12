# One secret per service. Passwords are generated here and never leave Secrets Manager: External
# Secrets Operator turns them into Kubernetes Secrets; the database bootstrap job reads them to
# create the roles. Terraform state holds them (mark the state bucket as sensitive as it is).
resource "random_password" "db" {
  for_each = var.database_services
  length   = 40
  special  = false
}

resource "aws_secretsmanager_secret" "db" {
  for_each   = var.database_services
  name       = "travelos/${var.environment}/${each.key}/db"
  kms_key_id = var.kms_key_arn
  tags       = merge(var.tags, { service = each.key })
}

resource "aws_secretsmanager_secret_version" "db" {
  for_each  = var.database_services
  secret_id = aws_secretsmanager_secret.db[each.key].id
  secret_string = jsonencode({
    username = "${replace(each.key, "-", "_")}_app"
    database = each.value
    password = random_password.db[each.key].result
  })
}

# Values a human sets after apply with `aws secretsmanager put-secret-value`; Terraform never
# overwrites them (ignore_changes) and never reads them back.
resource "aws_secretsmanager_secret" "llm" {
  name       = "travelos/${var.environment}/llm-gateway/anthropic"
  kms_key_id = var.kms_key_arn
  tags       = merge(var.tags, { service = "llm-gateway" })
}

resource "aws_secretsmanager_secret_version" "llm" {
  secret_id     = aws_secretsmanager_secret.llm.id
  secret_string = jsonencode({ provider = "fake", api_key = "" })
  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret" "payment" {
  name       = "travelos/${var.environment}/trip-planning/payment"
  kms_key_id = var.kms_key_arn
  tags       = merge(var.tags, { service = "trip-planning" })
}

resource "aws_secretsmanager_secret_version" "payment" {
  secret_id     = aws_secretsmanager_secret.payment.id
  secret_string = jsonencode({ token = "tok_corp_visa_sandbox" })
  lifecycle {
    ignore_changes = [secret_string]
  }
}
