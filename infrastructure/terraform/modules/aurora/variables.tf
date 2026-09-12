variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "allowed_security_group_ids" {
  description = "Who may connect on 5432 (the EKS cluster security group)."
  type        = list(string)
}
variable "kms_key_arn" { type = string }
variable "secrets_kms_key_arn" { type = string }
variable "engine_version" {
  type    = string
  default = "17.4"
}
variable "min_capacity" {
  type    = number
  default = 0.5
}
variable "max_capacity" {
  type    = number
  default = 4
}
variable "instance_count" {
  type    = number
  default = 2
}
variable "backup_retention_days" {
  type    = number
  default = 7
}
variable "deletion_protection" {
  type    = bool
  default = true
}
variable "tags" {
  type    = map(string)
  default = {}
}
