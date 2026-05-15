{
  cachix = {
    name = "scala-cli-nix";
    public-key = "scala-cli-nix.cachix.org-1:bWlFopClBMmKuqXQqsz3A+IeHzZAbU54Q/hUiVRluQ8=";
  };

  deploy = {
    # `nix run packages.x86_64-linux.deploy-server01` from the runner,
    # only on main. The deploy script reads DEPLOY_SSH_KEY (set below via
    # ssh-keys) and invokes deploy-rs against the `system` profile of the
    # server01 node defined in flake.nix — switch-to-configuration on the
    # target, with magic rollback.
    #
    # in-repo = true: deploy-rs resolves `.#server01` against the flake in
    # the current working directory, so the runner must execute from the
    # checked-out repo.
    server01 = {
      package = "packages.x86_64-linux.deploy-server01";
      branches = "default";
      in-repo = true;
      ssh-keys = [{
        secret = "DEPLOY_SSH_KEY";
        public-key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKtOMFEJkH37S3sHD3WS9XScOyx1b2noFgQ4edrxOcxE nix-ci@scala-cli-nix";
      }];
    };
  };
}
