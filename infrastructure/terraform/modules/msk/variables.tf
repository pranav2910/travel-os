variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" {
  description = "Three private subnets, one per AZ."
  type        = list(string)
}
variable "allowed_security_group_ids" { type = list(string) }
variable "kms_key_arn" { type = string }
variable "kafka_version" {
  type    = string
  default = "3.9.x"
}
variable "instance_type" {
  type    = string
  default = "kafka.t3.small"
}
variable "volume_size" {
  type    = number
  default = 100
}
variable "log_retention_days" {
  type    = number
  default = 30
}
variable "tags" {
  type    = map(string)
  default = {}
}

variable "logs_kms_key_arn" {
  description = "CMK for CloudWatch log groups."
  type        = string
}
