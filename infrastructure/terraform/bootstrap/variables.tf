variable "aws_region" {
  description = "Region for the state bucket and lock table."
  type        = string
}

variable "name_prefix" {
  description = "Prefix for the state bucket and lock table names (account id is appended)."
  type        = string
  default     = "travelos-tfstate"
}
