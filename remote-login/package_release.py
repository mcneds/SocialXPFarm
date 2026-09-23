"""Package only source/docs/tests and pinned dependencies; never local configuration."""
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parent
output = Path(sys.argv[1])
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(root.rglob('*')):
        relative = file.relative_to(root)
        if any(part.startswith('.') or part == '__pycache__' for part in relative.parts):
            continue
        # Explicit source directories and root files only; config/state files are never packaged.
        include = (len(relative.parts) == 1 and file.name in {
            'README.md', 'DEVELOPMENT.md', 'requirements.in', 'requirements.lock', 'dev_scenarios.py', 'package_release.py'})
        include |= relative.parts[0] in {'sxp_remote', 'tests'} and file.suffix in {'.py', '.json'}
        if file.is_file() and not file.is_symlink() and include:
            archive.write(file, Path('remote-login') / relative)
