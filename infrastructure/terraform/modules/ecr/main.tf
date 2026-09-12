# EKS pulls from ECR in the same account: immutable tags, scanned on push, encrypted with our key.
resource "aws_ecr_repository" "this" {
  for_each             = toset(var.services)
  name                 = "travel-os/${each.value}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
  tags = var.tags
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep the last 50 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 50 }
      action       = { type = "expire" }
    }]
  })
}
