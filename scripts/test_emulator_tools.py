"""Launch safeguards, tested without booting a VM or contacting a data source."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import emulator_tools as tools


class EmulatorLaunchTests(unittest.TestCase):
    def test_dns_override_is_optional_local_and_can_be_replaced_by_environment(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with patch.object(tools, 'ROOT', root), patch.dict(tools.os.environ, {}, clear=True):
                self.assertIsNone(tools.emulator_dns())
                (root / 'local.properties').write_text('glucowatch.emulator.dns=1.1.1.1, 8.8.8.8\n')
                command = tools.emulator_command(Path('sdk'), 'phone', 5554)
                self.assertEqual(command[command.index('-dns-server') + 1], '1.1.1.1,8.8.8.8')
                with patch.dict(tools.os.environ, {'GLUCOWATCH_EMULATOR_DNS': '9.9.9.9'}):
                    self.assertEqual(tools.emulator_dns(), '9.9.9.9')
                with patch.dict(tools.os.environ, {'GLUCOWATCH_EMULATOR_DNS': ''}):
                    self.assertIsNone(tools.emulator_dns())

    def test_invalid_dns_override_is_rejected_before_launch(self):
        for value in ('bad-name', '1.1.1.1,', ','.join(['1.1.1.1'] * 5)):
            with self.subTest(value=value), patch.dict(tools.os.environ, {'GLUCOWATCH_EMULATOR_DNS': value}):
                with self.assertRaises(ValueError):
                    tools.emulator_command(Path('sdk'), 'phone', 5554)

    def test_windows_visible_launch_avoids_host_gpu_and_snapshots(self):
        with patch.object(tools.sys, 'platform', 'win32'):
            command = tools.emulator_command(Path('sdk'), 'phone', 5554, headless=False)
        self.assertTrue(command[0].endswith('emulator.exe'))
        self.assertEqual(command[command.index('-gpu') + 1], 'software')
        self.assertIn('-no-snapshot', command)
        self.assertIn('-GuestAngle', command)
        self.assertIn('-Vulkan', command)
        self.assertNotIn('-no-window', command)
        self.assertNotIn('host', command)

    def test_invalid_or_undiscoverable_ports_are_rejected(self):
        for port in (5553, 5555, 5586, 5588, 5590):
            with self.subTest(port=port), self.assertRaises(ValueError):
                tools.emulator_command(Path('sdk'), 'phone', port)

    def test_non_windows_keeps_existing_gpu_selection(self):
        with patch.object(tools.sys, 'platform', 'linux'):
            command = tools.emulator_command(Path('sdk'), 'watch', 5580)
        self.assertIn('-no-window', command)
        self.assertNotIn('-gpu', command)
        self.assertNotIn('-Vulkan', command)

    def test_phone_guest_angle_workaround_is_kept_on_linux(self):
        with patch.object(tools.sys, 'platform', 'linux'):
            command = tools.emulator_command(Path('sdk'), 'phone', 5554, disable_guest_angle=True)
        self.assertIn('-GuestAngle', command)
        self.assertNotIn('-gpu', command)

    def test_windows_sdk_properties_are_unescaped(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'local.properties').write_text(r'sdk.dir=C\:\\Users\\liv\\Android\\Sdk')
            with patch.object(tools, 'ROOT', root), patch.dict(tools.os.environ, {}, clear=True):
                self.assertEqual(str(tools.sdk_dir()), r'C:\Users\liv\Android\Sdk')

    def test_occupied_adb_port_prevents_launch(self):
        with patch.object(tools.socket, 'socket') as mocked_socket, patch.object(tools.subprocess, 'Popen') as popen:
            mocked_socket.return_value.bind.side_effect = [None, OSError('occupied')]
            with self.assertRaisesRegex(RuntimeError, '5555 is occupied'):
                tools.start_emulator(Path('sdk'), 'phone', 5554)
            popen.assert_not_called()
            self.assertEqual(mocked_socket.return_value.close.call_count, 2)

    def test_log_is_retained_in_private_output_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(tools, 'ROOT', Path(folder)), patch.object(tools.socket, 'socket'), \
                    patch.object(tools.subprocess, 'Popen') as popen:
                process, log = tools.start_emulator(Path('sdk'), 'phone', 5554)
            self.assertEqual(process, popen.return_value)
            self.assertTrue(log.is_file())
            self.assertEqual(log.parent, Path(folder) / 'data/output/emulator')
            self.assertEqual(popen.call_args.kwargs['stderr'], subprocess.STDOUT)
            self.assertNotEqual(popen.call_args.kwargs['stdout'], subprocess.DEVNULL)


if __name__ == '__main__':
    unittest.main()
