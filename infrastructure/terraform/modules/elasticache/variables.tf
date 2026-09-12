variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "allowed_security_group_ids" { type = list(string) }
variable "kms_key_arn" { type = string }
variable "secrets_kms_key_arn" { type = string }
variable "node_type" {
  type    = string
  default = "cache.t4g.small"
}
variable "num_cache_clusters" {
  type    = number
  default = 2
}
variable "tags" {
  type    = map(string)
  default = {}
}
