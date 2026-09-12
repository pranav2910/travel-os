terraform {
  required_version = ">= 1.9"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
    tls    = { source = "hashicorp/tls", version = "~> 4.0" }
  }
  # State lives in the bootstrap bucket; bucket/key/region/lock table are passed at init time:
  #   terraform init -backend-config=backend.hcl   (copy backend.hcl.example, never commit real values)
  backend "s3" {}
}
