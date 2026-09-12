aws_region                 = "us-east-1"
environment                = "prod"
vpc_cidr                   = "10.30.0.0/16"
availability_zones         = ["us-east-1a", "us-east-1b", "us-east-1c"]
single_nat_gateway         = false
kubernetes_version         = "1.33"
eks_endpoint_public_access = false
node_groups = {
  general = { instance_types = ["m7g.xlarge"], min_size = 3, desired_size = 3, max_size = 12 }
  workflow = {
    instance_types = ["m7g.xlarge"], min_size = 3, desired_size = 3, max_size = 8
    labels         = { "travelos.io/workload" = "workflow" }
    taints         = [{ key = "travelos.io/workload", value = "workflow", effect = "NO_SCHEDULE" }]
  }
  ai = {
    instance_types = ["m7g.xlarge"], min_size = 2, desired_size = 2, max_size = 8
    labels         = { "travelos.io/workload" = "ai" }
    taints         = [{ key = "travelos.io/workload", value = "ai", effect = "NO_SCHEDULE" }]
  }
}
aurora_min_capacity        = 2
aurora_max_capacity        = 32
aurora_instance_count      = 3
aurora_deletion_protection = true
msk_instance_type          = "kafka.m7g.large"
msk_volume_size            = 500
enable_redis               = true
redis_node_type            = "cache.r7g.large"
