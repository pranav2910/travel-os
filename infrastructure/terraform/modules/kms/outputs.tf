output "key_arns" {
  value = merge({ for k, v in aws_kms_key.this : k => v.arn }, { logs = aws_kms_key.logs.arn })
}
output "key_ids" {
  value = merge({ for k, v in aws_kms_key.this : k => v.key_id }, { logs = aws_kms_key.logs.key_id })
}
