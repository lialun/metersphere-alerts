import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('alert_deploy', Path(__file__).with_name('deploy.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentTest(unittest.TestCase):
    def test_configuration_volume_is_explicitly_preserved(self):
        original = {'services': {'metersphere': {'image': deploy.BASE_IMAGE,
                    'volumes': ['/srv/ms/data:/opt/metersphere/data'], 'environment': {'EXISTING': 'preserve'}}}}
        saved = copy.deepcopy(original)
        result = deploy.candidate(original, 'metersphere-alerts:revision', [
            {'Destination': '/opt/metersphere/conf', 'Type': 'volume', 'Name': 'original-conf'}])
        self.assertEqual(original, saved)
        self.assertEqual(result['volumes']['preserved_conf'], {'external': True, 'name': 'original-conf'})
        self.assertEqual(result['services']['metersphere']['environment'], {'EXISTING': 'preserve'})
        self.assertIn('/srv/ms/data:/opt/metersphere/data', result['services']['metersphere']['volumes'])

    def test_rejects_unknown_image_or_missing_configuration_volume(self):
        config = {'services': {'metersphere': {'image': 'unrelated:latest'}}}
        with self.assertRaises(RuntimeError):
            deploy.candidate(config, 'metersphere-alerts:revision', [])
        config['services']['metersphere']['image'] = deploy.BASE_IMAGE
        with self.assertRaises(RuntimeError):
            deploy.candidate(config, 'metersphere-alerts:revision', [])

    def test_bundle_rejects_tampered_files(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'a').write_bytes(b'changed')
            (base / 'manifest.json').write_text(json.dumps({'sourceCommit': 'expected', 'baseImage': deploy.BASE_IMAGE,
                'files': {'a': hashlib.sha256(b'original').hexdigest()}}))
            with self.assertRaisesRegex(RuntimeError, 'checksum'):
                deploy.checked_bundle(base, 'expected')

    def test_unreadable_candidate_keeps_existing_service_running(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'data').mkdir()
            encoded = json.dumps({'services': {'metersphere': {'image': deploy.BASE_IMAGE}}}).encode()
            (base / 'compose.yaml').write_bytes(encoded)
            before = {'Config': {'Image': deploy.BASE_IMAGE, 'Labels': {
                'com.docker.compose.project': 'test', 'com.docker.compose.service': 'metersphere'}},
                'Mounts': [{'Type': 'volume', 'Name': 'original-conf', 'Source': '/synthetic/conf',
                            'Destination': '/opt/metersphere/conf'}]}
            commands = []

            def fake_run(args, **kwargs):
                commands.append(args)
                if args[:2] == ['docker', 'inspect']:
                    return json.dumps([before])
                if args[:3] == ['docker', 'image', 'inspect']:
                    return 'expected'
                if args[:4] == ['docker', 'exec', '--user', '1000']:
                    raise RuntimeError('synthetic unreadable artifact')
                return ''

            argv = ['deploy.py', '--bundle', directory, '--commit', 'expected', '--base', directory,
                    '--project', 'test', '--container', 'test-ms']
            with patch('sys.argv', argv), patch.object(deploy, 'checked_bundle', return_value={}), \
                    patch.object(deploy, 'run', side_effect=fake_run), patch.object(deploy.os, 'geteuid', return_value=0):
                with self.assertRaisesRegex(RuntimeError, 'unreadable artifact'):
                    deploy.main()
            self.assertEqual((base / 'compose.yaml').read_bytes(), encoded)
            self.assertFalse(any('stop' in command or 'up' in command for command in commands))
            self.assertTrue(any(command[:4] == ['docker', 'rm', '-f', '-v'] for command in commands))

    def test_backup_failure_restarts_original_image_and_watchdog(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'data').mkdir()
            original = {'services': {'metersphere': {'image': deploy.BASE_IMAGE}}}
            encoded = json.dumps(original).encode()
            (base / 'compose.yaml').write_bytes(encoded)
            before = {'Config': {'Image': deploy.BASE_IMAGE, 'Labels': {
                'com.docker.compose.project': 'test', 'com.docker.compose.service': 'metersphere'}},
                'Image': 'sha256:old', 'Mounts': [{'Type': 'volume', 'Name': 'original-conf',
                    'Source': '/synthetic/conf', 'Destination': '/opt/metersphere/conf'}]}
            commands = []

            def fake_run(args, **kwargs):
                commands.append(args)
                if args[:2] == ['docker', 'inspect']:
                    return json.dumps([before])
                if args[:3] == ['docker', 'image', 'inspect']:
                    return 'expected'
                if args[:2] == ['tar', '--xattrs']:
                    raise RuntimeError('synthetic backup failure')
                return ''

            argv = ['deploy.py', '--bundle', directory, '--commit', 'expected', '--base', directory,
                    '--project', 'test', '--container', 'test-ms', '--watchdog-unit', 'test.timer']
            with patch('sys.argv', argv), patch.object(deploy, 'checked_bundle', return_value={}), \
                    patch.object(deploy, 'run', side_effect=fake_run), patch.object(deploy.os, 'geteuid', return_value=0), \
                    patch.object(deploy.subprocess, 'run') as systemctl:
                systemctl.return_value.returncode = 0
                systemctl.return_value.stdout = '{"data":"synthetic-public-key"}'
                with self.assertRaisesRegex(RuntimeError, 'release failed'):
                    deploy.main()
            self.assertEqual((base / 'compose.yaml').read_bytes(), encoded)
            self.assertTrue(any('up' in command for command in commands))
            self.assertEqual(commands[-1], ['systemctl', 'start', 'test.timer'])


if __name__ == '__main__':
    unittest.main()
