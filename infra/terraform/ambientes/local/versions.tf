terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.67"
    }
  }

  # Estado em arquivo local (terraform.tfstate nesta pasta, fora do git): um ambiente só, uma
  # pessoa só. Em time, o padrão é backend "s3" com trava (ver ADR 0014).
}
