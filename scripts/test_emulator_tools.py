"""Launch safeguards, tested without booting a VM or contacting a data source."""
import socket
import subprocess
import sys
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

    def test_live_listener_is_refused_without_waiting(self):
        # create_server listens with SO_REUSEADDR on POSIX, as the emulator's sockets may.
        with socket.create_server(('127.0.0.1', 0)) as server, \
                patch.object(tools.time, 'sleep', side_effect=AssertionError('waited for a live port')):
            port = server.getsockname()[1]
            self.assertTrue(tools.port_in_use(port))
            with self.assertRaisesRegex(RuntimeError, f'{port} is occupied'):
                tools.wait_for_port(port)

    def test_connections_left_by_a_closed_listener_are_not_a_live_emulator(self):
        for reuse in (True, False):
            with self.subTest(reuse=reuse):
                port = left_in_time_wait(reuse)
                self.assertFalse(tools.port_listening(port))
                try:
                    tools.wait_for_port(port, timeout=0)
                except RuntimeError as error:
                    self.assertIn('closed connections', str(error))

    @unittest.skipIf(sys.platform == 'win32', 'Windows binds over TIME_WAIT without a reuse flag')
    def test_probe_passes_over_time_wait_of_a_reusable_listener(self):
        port = left_in_time_wait(reuse=True)
        with socket.socket() as plain:
            # The probe this replaced: refused for about a minute after an emulator closed.
            self.assertRaises(OSError, plain.bind, ('127.0.0.1', port))
        self.assertFalse(tools.port_in_use(port))

    def test_closed_connections_are_waited_out_once_and_then_launch_proceeds(self):
        with patch.object(tools, 'port_in_use', side_effect=[True, True, False]), \
                patch.object(tools, 'port_listening', return_value=False), \
                patch.object(tools.time, 'sleep') as sleep, patch('builtins.print') as printed:
            tools.wait_for_port(5580)
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(printed.call_count, 1)

    def test_closed_connections_that_outlast_the_timeout_stop_the_launch(self):
        with patch.object(tools, 'port_in_use', return_value=True), \
                patch.object(tools, 'port_listening', return_value=False), \
                patch.object(tools.time, 'sleep'), patch('builtins.print'):
            with self.assertRaisesRegex(RuntimeError, '5581 is still held by closed connections'):
                tools.wait_for_port(5581, timeout=0)

    def test_closing_waits_until_the_emulator_stops_listening(self):
        with patch.object(tools, 'port_listening', side_effect=[True, False, False]) as listening, \
                patch.object(tools.time, 'sleep') as sleep:
            self.assertTrue(tools.wait_until_closed(5580))
        self.assertEqual(sleep.call_count, 1)
        self.assertEqual([c.args[0] for c in listening.call_args_list], [5580, 5580, 5581])

    def test_closing_gives_up_with_a_message_instead_of_hanging(self):
        with patch.object(tools, 'port_listening', return_value=True), \
                patch.object(tools.time, 'sleep'), patch('builtins.print') as printed:
            self.assertFalse(tools.wait_until_closed(5554, timeout=0))
        self.assertIn('5554 is still running', printed.call_args.args[0])

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


def left_in_time_wait(reuse):
    """A loopback port whose listener closed after the server side ended a connection first,
    which leaves that side in TIME_WAIT, as a killed emulator leaves its console and adb ports."""
    server = socket.socket()
    if reuse:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('127.0.0.1', 0))
    server.listen()
    port = server.getsockname()[1]
    client = socket.create_connection(('127.0.0.1', port))
    accepted, _ = server.accept()
    accepted.close()
    client.close()
    server.close()
    return port


if __name__ == '__main__':
    unittest.main()
