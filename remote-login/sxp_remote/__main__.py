import argparse
from pathlib import Path
from .config import DEFAULT_PATH, load, setup


def main():
    parser = argparse.ArgumentParser(description='SocialXPFarm private Discord login companion')
    parser.add_argument('command', choices=['setup', 'serve', 'service-unit', 'web-config', 'web-enable', 'web-disable', 'tunnel-config', 'tunnel-service-unit'])
    parser.add_argument('--config', type=Path, default=DEFAULT_PATH)
    parser.add_argument('--client-id')
    parser.add_argument('--redirect-uri', default='https://auth.mcneds.dev/oauth/callback')
    parser.add_argument('--callback-port', type=int, default=38472)
    parser.add_argument('--instance')
    parser.add_argument('--tunnel-id')
    parser.add_argument('--tunnel-config', type=Path, default=DEFAULT_PATH.parent / 'tunnel.yml')
    parser.add_argument('--cloudflared', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'setup':
            setup(args.config)
        elif args.command == 'web-config':
            if not args.client_id:
                parser.error('--client-id is required')
            from .web_setup import configure
            configure(args.config, args.client_id, args.redirect_uri, args.callback_port)
            print('Callback configured. Restart the companion. Instances retain their existing login until web-enable.')
        elif args.command in {'web-enable', 'web-disable'}:
            if not args.instance:
                parser.error('--instance is required; enable one test instance first')
            from .web_setup import instance_config
            instance_config(args.config, args.instance, args.command == 'web-enable')
            print('Selected instance updated. Restart that Minecraft instance. Saved renewal credentials were preserved.')
        elif args.command == 'tunnel-config':
            if not args.tunnel_id:
                parser.error('--tunnel-id is required')
            from .web_setup import tunnel_config, write_generated
            import uuid
            tunnel_id = str(uuid.UUID(args.tunnel_id))
            content = tunnel_config(load(args.config), tunnel_id, Path.home() / '.cloudflared' / (tunnel_id + '.json'))
            write_generated(args.output or args.tunnel_config, content)
            print('Callback-only tunnel configuration written.')
        elif args.command == 'tunnel-service-unit':
            from .web_setup import tunnel_unit, write_generated
            content = tunnel_unit(args.tunnel_config, args.cloudflared)
            if args.output:
                write_generated(args.output, content)
            else:
                print(content, end='')
        elif args.command == 'service-unit':
            from .service import unit
            content = unit(args.config)
            if args.output:
                from .web_setup import write_generated
                write_generated(args.output, content)
            else:
                print(content, end='')
        else:
            from .app import run
            run(load(args.config), args.config.parent / 'state.json', args.config)
    except (ValueError, OSError, KeyError):
        parser.exit(1, 'Configuration/setup failed. Check the local file, permissions and required values.\n')


if __name__ == '__main__':
    main()
