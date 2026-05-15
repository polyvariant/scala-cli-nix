terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.48"
    }
  }
}

variable "hcloud_token" {
  type      = string
  sensitive = true
}

variable "ssh_key_name" {
  type = string
}

variable "server_type" {
  type    = string
  default = "cx22"
}

variable "server_location" {
  type    = string
  default = "fsn1"
}

provider "hcloud" {
  token = var.hcloud_token
}

data "hcloud_ssh_key" "me" {
  name = var.ssh_key_name
}

resource "hcloud_server" "server01" {
  name        = "server01"
  image       = "debian-12"
  server_type = var.server_type
  location    = var.server_location
  ssh_keys    = [data.hcloud_ssh_key.me.name]

  lifecycle {
    ignore_changes = [ssh_keys, image]
  }
}

module "deploy" {
  source = "github.com/nix-community/nixos-anywhere//terraform/all-in-one"

  nixos_system_attr      = ".#nixosConfigurations.server01.config.system.build.toplevel"
  nixos_partitioner_attr = ".#nixosConfigurations.server01.config.system.build.diskoScriptNoDeps"

  target_host   = hcloud_server.server01.ipv4_address
  instance_id   = hcloud_server.server01.id
  debug_logging = true
}

output "ipv4" {
  value = hcloud_server.server01.ipv4_address
}
