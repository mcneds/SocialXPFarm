from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from sxp_remote.service import unit


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def generate(self, name):
        directory = self.root / name
        (directory / 'sxp_remote').mkdir(parents=True)
        interpreter = directory / 'python'
        interpreter.symlink_to(sys.executable)
        config = directory / 'private config.json'
        with patch('sxp_remote.service.__file__', str(directory / 'sxp_remote/service.py')), \
                patch('sxp_remote.service.sys.executable', str(interpreter)):
            return directory, unit(config)

    def test_working_directory_is_unquoted_and_preserves_spaces_and_percent(self):
        for name in ('companion', 'companion with spaces', 'companion %n 100%'):
            with self.subTest(path=name):
                directory, text = self.generate(name)
                setting = next(line for line in text.splitlines() if line.startswith('WorkingDirectory='))
                self.assertEqual('WorkingDirectory=' + str(directory).replace('%', '%%'), setting)

    def test_exec_start_keeps_each_path_quoted(self):
        directory, text = self.generate('companion space %n')
        escaped = str(directory).replace('%', '%%')
        self.assertIn(f'ExecStart="{escaped}/python" -m sxp_remote serve --config "{escaped}/private config.json"\n', text)

    @unittest.skipUnless(shutil.which('systemd-analyze'), 'systemd-analyze required; mandatory in Linux CI')
    def test_generated_units_pass_systemd_verification(self):
        for index, name in enumerate(('ordinary', 'with spaces', 'with spaces %n 100%')):
            with self.subTest(path=name):
                _, text = self.generate(name)
                service = self.root / f'generated-{index}.service'
                service.write_text(text)
                result = subprocess.run(['systemd-analyze', 'verify', '--man=no', str(service)],
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
