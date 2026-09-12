variable "environment" { type = string }
variable "kms_key_arn" { type = string }
variable "database_services" {
  description = "Services that own a database; a role named <service>_app and a generated password each."
  type        = map(string) # service name -> database name
}
variable "tags" {
  type    = map(string)
  default = {}
}
