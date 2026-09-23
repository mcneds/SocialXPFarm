import argparse
from pathlib import Path
from .config import DEFAULT_PATH, load, setup


def main():
    parser = argparse.ArgumentParser(description='SocialXPFarm private Discord login companion')
    parser.add_argument('command', choices=['setup', 'serve', 'service-unit'])
    parser.add_argument('--config', type=Path, default=DEFAULT_PATH)
    args = parser.parse_args()
    try:
        if args.command == 'setup':
            setup(args.config)
        elif args.command == 'service-unit':
            from .service import unit
            print(unit(args.config), end='')
        else:
            from .app import run
            run(load(args.config), args.config.parent / 'state.json', args.config)
    except (ValueError, OSError, KeyError):
        parser.exit(1, 'Configuration/setup failed. Check the local file, permissions and required values.\n')


if __name__ == '__main__':
    main()
