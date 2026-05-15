{ config, lib, ... }:

let
  cfg = config.services.http-apps;

  appOpts = { name, ... }: {
    options = {
      package = lib.mkOption {
        type = lib.types.package;
        description = ''
          Derivation that provides the HTTP server binary. The module invokes
          `lib.getExe`, so the package must set `meta.mainProgram` (every
          derivation produced by `buildScalaCliApp(s)` does).
        '';
      };

      domain = lib.mkOption {
        type = lib.types.str;
        example = "myapp.example.com";
        description = "Public hostname served by caddy; reverse-proxied to 127.0.0.1:<port>.";
      };

      port = lib.mkOption {
        type = lib.types.port;
        description = "Loopback TCP port the app listens on. Exposed to the unit as $PORT.";
      };

      environment = lib.mkOption {
        type = lib.types.attrsOf lib.types.str;
        default = { };
        description = "Extra environment variables for the systemd unit.";
      };
    };
  };
in
{
  options.services.http-apps = lib.mkOption {
    type = lib.types.attrsOf (lib.types.submodule appOpts);
    default = { };
    description = ''
      Declarative HTTP applications. Each entry runs as a `DynamicUser` systemd
      unit bound to a loopback port and is fronted by caddy on the configured
      domain. Caddy is enabled automatically when at least one app is declared.
    '';
  };

  config = lib.mkIf (cfg != { }) {
    systemd.services = lib.mapAttrs' (name: app: {
      name = "http-app-${name}";
      value = {
        description = "http-app ${name}";
        wantedBy = [ "multi-user.target" ];
        after = [ "network.target" ];
        environment = app.environment // { PORT = toString app.port; };
        serviceConfig = {
          ExecStart = lib.getExe app.package;
          Restart = "on-failure";
          RestartSec = "10s";
          DynamicUser = true;
        };
      };
    }) cfg;

    services.caddy = {
      enable = true;
      virtualHosts = lib.mapAttrs' (_name: app: {
        name = app.domain;
        value.extraConfig = ''
          reverse_proxy 127.0.0.1:${toString app.port}
        '';
      }) cfg;
    };

    networking.firewall.allowedTCPPorts = [ 80 443 ];
  };
}
