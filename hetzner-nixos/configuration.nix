{
  modulesPath,
  lib,
  pkgs,
  ...
}:
let
  hello-http4s-native = pkgs.callPackage ../examples/hello-http4s-native/derivation.nix { };
in
{
  imports = [
    (modulesPath + "/profiles/qemu-guest.nix")
  ];

  systemd.services.hello-http4s = {
    description = "hello-http4s native http4s server";
    wantedBy = [ "multi-user.target" ];
    after = [ "network.target" ];
    serviceConfig = {
      ExecStart = lib.getExe hello-http4s-native;
      Restart = "on-failure";
      RestartSec = "10s";
      DynamicUser = true;
    };
  };

  services.caddy = {
    enable = true;
    virtualHosts."hello-native.scala-cli-nix.kubukoz.com".extraConfig = ''
      reverse_proxy 127.0.0.1:8080
    '';
  };

  networking.firewall.allowedTCPPorts = [ 80 443 ];

  # Hetzner cloud cx-series boots in BIOS mode (not UEFI). Disko needs a
  # 1MiB bios_grub partition for GRUB's stage 1.5 to live in on a GPT disk,
  # and the bootloader has to be GRUB targeting the whole disk.
  disko.devices.disk.main = {
    type = "disk";
    device = "/dev/sda";
    content = {
      type = "gpt";
      partitions = {
        boot = {
          size = "1M";
          type = "EF02"; # BIOS boot partition
        };
        root = {
          size = "100%";
          content = {
            type = "filesystem";
            format = "ext4";
            mountpoint = "/";
          };
        };
      };
    };
  };

  # `device` is provided by disko's GRUB integration based on disk.main.
  boot.loader.grub = {
    enable = true;
    efiSupport = false;
  };

  networking.hostName = "server01";
  networking.useDHCP = lib.mkDefault true;

  services.openssh.enable = true;
  services.openssh.settings.PasswordAuthentication = false;
  services.openssh.settings.PermitRootLogin = "prohibit-password";

  users.users.root.openssh.authorizedKeys.keys = [
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJbf71nFwkbLYlyceqJe35I4rHVc/8apmenfSQPVVzxF kubukoz@kubukoz-max.local"
    # nix-ci deploy key (see nix-ci.nix deploy.server01).
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKtOMFEJkH37S3sHD3WS9XScOyx1b2noFgQ4edrxOcxE nix-ci@scala-cli-nix"
  ];

  environment.systemPackages = with pkgs; [
    git
    vim
  ];

  system.stateVersion = "24.11";
}
