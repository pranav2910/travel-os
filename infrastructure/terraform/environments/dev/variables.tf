variable "aws_region" { type = string }
variable "environment" { type = string }
variable "vpc_cidr" { type = string }
variable "availability_zones" {
  description = "Three AZ names in aws_region, e.g. [\"us-east-1a\",\"us-east-1b\",\"us-east-1c\"]."
  type        = list(string)
}
variable "single_nat_gateway" {
  type    = bool
  default = false
}
variable "kubernetes_version" {
  type    = string
  default = "1.33"
}
variable "eks_endpoint_public_access" {
  type    = bool
  default = false
}
variable "eks_public_access_cidrs" {
  description = "Operator CIDRs allowed to reach a public API endpoint; empty in prod."
  type        = list(string)
  default     = []
}
variable "node_groups" {
  type = map(object({
    instance_types = list(string)
    min_size       = number
    desired_size   = number
    max_size       = number
    labels         = optional(map(string), {})
    taints = optional(list(object({
      key    = string
      value  = optional(string)
      effect = string
    })), [])
  }))
}
variable "aurora_min_capacity" { type = number }
variable "aurora_max_capacity" { type = number }
variable "aurora_instance_count" { type = number }
variable "aurora_deletion_protection" { type = bool }
variable "msk_instance_type" { type = string }
variable "msk_volume_size" { type = number }
variable "enable_redis" {
  description = "No service uses Redis yet (search cache / rate limits are a later slice)."
  type        = bool
  default     = false
}
variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}
