variable "name" { type = string }
variable "oidc_provider_arn" { type = string }
variable "oidc_provider_url" {
  description = "Issuer host/path without https://."
  type        = string
}
variable "secrets_kms_key_arn" { type = string }
variable "secret_name_prefix" {
  description = "Secrets Manager prefix the External Secrets Operator may read, e.g. travelos/dev/."
  type        = string
}
variable "audit_bucket_arn" { type = string }
variable "tags" {
  type    = map(string)
  default = {}
}
