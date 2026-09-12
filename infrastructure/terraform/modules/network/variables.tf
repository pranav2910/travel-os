variable "name" { type = string }
variable "cidr" { type = string }
variable "azs" {
  description = "Exactly three availability zones."
  type        = list(string)
  validation {
    condition     = length(var.azs) == 3
    error_message = "Three availability zones are required."
  }
}
variable "single_nat_gateway" {
  description = "One NAT for all private subnets (dev cost saving) instead of one per AZ."
  type        = bool
  default     = false
}
variable "flow_logs_retention_days" {
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
