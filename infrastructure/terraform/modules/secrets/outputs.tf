output "secret_arns" {
  value = merge(
    { for k, v in aws_secretsmanager_secret.db : "${k}/db" => v.arn },
    { "llm-gateway/anthropic" = aws_secretsmanager_secret.llm.arn, "trip-planning/payment" = aws_secretsmanager_secret.payment.arn },
  )
}
output "secret_name_prefix" { value = "travelos/${var.environment}/" }
