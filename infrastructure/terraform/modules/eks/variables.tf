variable "name" { type = string }
variable "kubernetes_version" { type = string }
variable "vpc_id" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "kms_key_arn" {
  description = "Envelope-encrypts Kubernetes secrets."
  type        = string
}
variable "endpoint_public_access" {
  description = "Public API endpoint (dev convenience). Private access is always on."
  type        = bool
  default     = false
}
variable "public_access_cidrs" {
  type    = list(string)
  default = []
}
variable "log_retention_days" {
  type    = number
  default = 90
}
variable "node_groups" {
  description = "Managed node groups keyed by name (the design's general / workflow / ai pools)."
  type = map(object({
    instance_types = list(string)
    ami_type       = optional(string, "AL2023_ARM_64_STANDARD")
    capacity_type  = optional(string, "ON_DEMAND")
    min_size       = number
    desired_size   = number
    max_size       = number
    disk_size      = optional(number, 50)
    labels         = optional(map(string), {})
    taints = optional(list(object({
      key    = string
      value  = optional(string)
      effect = string
    })), [])
  }))
}
variable "tags" {
  type    = map(string)
  default = {}
}

variable "logs_kms_key_arn" {
  description = "CMK for CloudWatch log groups."
  type        = string
}
