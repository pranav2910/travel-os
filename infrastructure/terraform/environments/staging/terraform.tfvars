aws_region                 = "us-east-1"
environment                = "staging"
vpc_cidr                   = "10.20.0.0/16"
availability_zones         = ["us-east-1a", "us-east-1b", "us-east-1c"]
single_nat_gateway         = false
kubernetes_version         = "1.33"
eks_endpoint_public_access = false
node_groups = {
  general = { instance_types = ["m7g.large"], min_size = 3, desired_size = 3, max_size = 6 }
  workflow = {
    instance_types = ["m7g.large"], min_size = 2, desired_size = 2, max_size = 4
    labels         = { "travelos.io/workload" = "workflow" }
  }
  ai = {
    instance_types = ["m7g.xlarge"], min_size = 1, desired_size = 2, max_size = 4
    labels         = { "travelos.io/workload" = "ai" }
  }
}
aurora_min_capacity        = 1
aurora_max_capacity        = 8
aurora_instance_count      = 2
aurora_deletion_protection = true
msk_instance_type          = "kafka.m7g.large"
msk_volume_size            = 200
enable_redis               = true
