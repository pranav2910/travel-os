provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      project     = "travelos"
      environment = var.environment
      managed_by  = "terraform"
    }
  }
}

locals {
  name = "travelos-${var.environment}"
  # Services that own a database (ADR-0006), and the database each owns.
  database_services = {
    travel-core        = "travel_core"
    policy             = "policy"
    supplier-gateway   = "supplier_gateway"
    order              = "orders"
    audit              = "audit"
    enterprise-context = "enterprise_context"
  }
  services = ["travel-core", "policy", "supplier-gateway", "order", "audit", "trip-planning", "optimization", "llm-gateway"]
}

module "network" {
  source             = "../../modules/network"
  name               = local.name
  cidr               = var.vpc_cidr
  azs                = var.availability_zones
  single_nat_gateway = var.single_nat_gateway
  logs_kms_key_arn   = module.kms.key_arns["logs"]
}

module "kms" {
  source = "../../modules/kms"
  name   = var.environment
}

module "eks" {
  source                 = "../../modules/eks"
  name                   = local.name
  kubernetes_version     = var.kubernetes_version
  vpc_id                 = module.network.vpc_id
  private_subnet_ids     = module.network.private_subnet_ids
  kms_key_arn            = module.kms.key_arns["eks"]
  endpoint_public_access = var.eks_endpoint_public_access
  public_access_cidrs    = var.eks_public_access_cidrs
  node_groups            = var.node_groups
  logs_kms_key_arn       = module.kms.key_arns["logs"]
}

module "aurora" {
  source                     = "../../modules/aurora"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.database_subnet_ids
  allowed_security_group_ids = [module.eks.cluster_security_group_id]
  kms_key_arn                = module.kms.key_arns["rds"]
  secrets_kms_key_arn        = module.kms.key_arns["secrets"]
  min_capacity               = var.aurora_min_capacity
  max_capacity               = var.aurora_max_capacity
  instance_count             = var.aurora_instance_count
  deletion_protection        = var.aurora_deletion_protection
}

module "msk" {
  source                     = "../../modules/msk"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  allowed_security_group_ids = [module.eks.cluster_security_group_id]
  kms_key_arn                = module.kms.key_arns["msk"]
  instance_type              = var.msk_instance_type
  volume_size                = var.msk_volume_size
  logs_kms_key_arn           = module.kms.key_arns["logs"]
}

module "elasticache" {
  count                      = var.enable_redis ? 1 : 0
  source                     = "../../modules/elasticache"
  name                       = local.name
  vpc_id                     = module.network.vpc_id
  subnet_ids                 = module.network.private_subnet_ids
  allowed_security_group_ids = [module.eks.cluster_security_group_id]
  kms_key_arn                = module.kms.key_arns["cache"]
  secrets_kms_key_arn        = module.kms.key_arns["secrets"]
  node_type                  = var.redis_node_type
}

module "secrets" {
  source            = "../../modules/secrets"
  environment       = var.environment
  kms_key_arn       = module.kms.key_arns["secrets"]
  database_services = local.database_services
}

module "s3" {
  source      = "../../modules/s3"
  name        = var.environment
  kms_key_arn = module.kms.key_arns["s3"]
}

module "ecr" {
  source      = "../../modules/ecr"
  services    = local.services
  kms_key_arn = module.kms.key_arns["s3"]
}

module "irsa" {
  source              = "../../modules/irsa"
  name                = local.name
  oidc_provider_arn   = module.eks.oidc_provider_arn
  oidc_provider_url   = module.eks.oidc_provider_url
  secrets_kms_key_arn = module.kms.key_arns["secrets"]
  secret_name_prefix  = module.secrets.secret_name_prefix
  audit_bucket_arn    = module.s3.audit_bucket_arn
  msk_cluster_arn     = module.msk.cluster_arn
}
