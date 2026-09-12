variable "name" { type = string }
variable "purposes" {
  description = "One customer-managed key per purpose."
  type        = list(string)
  default     = ["eks", "rds", "msk", "s3", "secrets", "cache"]
}
variable "tags" {
  type    = map(string)
  default = {}
}
