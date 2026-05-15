{
  cachix = {
    name = "scala-cli-nix";
    public-key = "scala-cli-nix.cachix.org-1:bWlFopClBMmKuqXQqsz3A+IeHzZAbU54Q/hUiVRluQ8=";
  };

  deploy = {
    # `nix run packages.x86_64-linux.deploy-server01` from the runner,
    # only on main. The deploy script reads NIX_CI_DEPLOY_SSH_KEY (set
    # below via ssh-keys) and runs `nixos-rebuild switch --target-host`
    # against the Hetzner box defined in hetzner-nixos/.
    server01 = {
      package = "packages.x86_64-linux.deploy-server01";
      branches = "default";
      ssh-keys = [{
        secret = "DEPLOY_SSH_KEY";
        public-key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKtOMFEJkH37S3sHD3WS9XScOyx1b2noFgQ4edrxOcxE nix-ci@scala-cli-nix";
      }];
    };
  };
}
