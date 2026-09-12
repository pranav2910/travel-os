variable "name" { type = string }
variable "kms_key_arn" { type = string }
variable "glacier_after_days" {
  type    = number
  default = 90
}
variable "tags" {
  type    = map(string)
  default = {}
}
