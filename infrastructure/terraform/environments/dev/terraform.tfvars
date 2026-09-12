# Non-secret sizing for dev. Region and AZs are inputs, not assumptions.
aws_region                 = "us-east-1"
environment                = "dev"
vpc_cidr                   = "10.10.0.0/16"
availability_zones         = ["us-east-1a", "us-east-1b", "us-east-1c"]
single_nat_gateway         = true
kubernetes_version         = "1.33"
eks_endpoint_public_access = true
eks_public_access_cidrs    = [] # add your operator CIDR at apply time: -var='eks_public_access_cidrs=["x.x.x.x/32"]'
node_groups = {
  general = { instance_types = ["m7g.large"], min_size = 2, desired_size = 2, max_size = 4 }
  workflow = {
    instance_types = ["m7g.large"], min_size = 1, desired_size = 1, max_size = 3
    labels         = { "travelos.io/workload" = "workflow" }
  }
  ai = {
    instance_types = ["m7g.large"], min_size = 1, desired_size = 1, max_size = 3
    labels         = { "travelos.io/workload" = "ai" }
  }
}
aurora_min_capacity        = 0.5
aurora_max_capacity        = 4
aurora_instance_count      = 1
aurora_deletion_protection = false
msk_instance_type          = "kafka.t3.small"
msk_volume_size            = 50
enable_redis               = false
