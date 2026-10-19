import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import subprocess

from diagnose_downloads import download_metadata, summarize, record


class DownloadDiagnosticsTest(unittest.TestCase):
    def test_device_disconnect_still_writes_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            process = Mock()
            process.poll.return_value = 1
            offline = subprocess.TimeoutExpired('adb', 10)
            with patch('diagnose_downloads.ROOT', Path(directory)), \
                    patch('diagnose_downloads.sdk_dir'), patch('diagnose_downloads.sdk_tool', return_value='adb'), \
                    patch('diagnose_downloads.subprocess.run', return_value=Mock(stdout='device')), \
                    patch('diagnose_downloads.subprocess.Popen', return_value=process), \
                    patch('diagnose_downloads.collect_firefox_logs', side_effect=offline), \
                    patch('diagnose_downloads.adb_command', side_effect=offline):
                record('emulator-5554', 1)
            reports = list(Path(directory).rglob('summary.json'))
            self.assertEqual(1, len(reports))
            self.assertEqual('logcat_disconnected', json.loads(reports[0].read_text())['capture']['stopped'])

    def test_metadata_hides_names_and_preserves_partial_size(self):
        raw = ('Row: 0 _id=12, _display_name=Private Patient, export.csv, _size=393216, '
               'date_modified=123, is_pending=0\n'
               'Row: 1 _id=13, _display_name=report.pdf, _size=42, date_modified=123, is_pending=0')
        files = download_metadata(raw)
        self.assertEqual(1, len(files))
        self.assertEqual(393216, files[0]['bytes'])
        self.assertNotIn('Private', json.dumps(files))
        self.assertNotIn('complete', files[0])

    def test_summary_never_copies_messages_or_treats_stable_file_as_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'android.log').write_text(
                '10-18 23:19:45.631 1 1 I Log: AppAction$DownloadInProgress\n'
                '10-18 23:19:48.895 1 1 I Log: AppAction$DownloadFailed\n'
                'Secret cookie=token https://example.com/private NS_ERROR_NET_RESET 0x804b0014\n',
                encoding='utf-8')
            sample = dict(time='now', files=[dict(id='12', name_hash='abc', bytes=393216, modified=123, pending=False)])
            (folder / 'samples.jsonl').write_text((json.dumps(sample) + '\n') * 2, encoding='utf-8')
            report = summarize(folder)
            self.assertEqual(['DownloadInProgress', 'DownloadFailed'], [e['event'] for e in report['events']])
            self.assertEqual(['0x804b0014', 'NS_ERROR_NET_RESET'], report['network_error_codes'])
            self.assertEqual(1, len(report['file_changes']))
            self.assertNotIn('token', json.dumps(report))
            self.assertNotIn('https://', json.dumps(report))

    def test_native_logs_and_app_journal_are_included_without_extra_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'network.log.moz_log.0').write_text('NS_ERROR_NET_TIMEOUT secret=value\n', encoding='utf-8')
            (folder / 'android.log').write_text('AndroidRuntime: ForegroundServiceDidNotStartInTimeException\n', encoding='utf-8')
            (folder / 'app-export.jsonl').write_text(json.dumps(dict(
                event='folder_rejected', bytes=393216, stage='parse', errorType='IllegalArgumentException',
                message='Private patient', url='https://example.com/token')) + '\n', encoding='utf-8')
            report = summarize(folder)
            self.assertEqual(['NS_ERROR_NET_TIMEOUT'], report['network_error_codes'])
            self.assertEqual(['ForegroundServiceDidNotStartInTimeException'], report['android_failures'])
            self.assertEqual('folder_rejected', report['app_events'][0]['event'])
            self.assertNotIn('Private patient', json.dumps(report))
            self.assertNotIn('https://', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
