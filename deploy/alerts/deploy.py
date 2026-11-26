#!/usr/bin/env python3
"""Upgrade an existing all-in-one instance from a verified CI bundle.

The environment's release pipeline supplies paths and identities. No credentials
are printed or copied off the host. Database recovery is a separate operation;
automatic rollback restores the exact application image and Compose file.
"""
import argparse
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.request

BASE_IMAGE = 'metersphere/metersphere-ce-allinone@sha256:a5344955efc15a51733e0a3b2a3ed07df51c991ecf88b2058290124ad9a3eac7'


def run(args, timeout=60, input=None):
    result = subprocess.run(args, input=input, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        # Compose/config output can contain secrets. Only report the executable.
        raise RuntimeError(f'{args[0]} failed (exit {result.returncode})')
    return result.stdout.strip()


def atomic(path, data, mode):
    if path.is_symlink():
        raise RuntimeError('Refusing a symlink deployment file')
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            os.fchmod(output.fileno(), mode)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def checked_bundle(bundle, expected_commit):
    manifest = json.loads((bundle / 'manifest.json').read_text())
    if manifest['sourceCommit'] != expected_commit or manifest['baseImage'] != BASE_IMAGE:
        raise RuntimeError('Unexpected application source or base image')
    for name, digest in manifest['files'].items():
        path = bundle / name
        if Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink() or not path.is_file():
            raise RuntimeError('Unsafe bundle path')
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Build artifact checksum mismatch')
    required = {'deploy/alerts/Dockerfile', 'frontend/dist/index.html'}
    if not required.issubset(manifest['files']) or sum(name.endswith('.jar') for name in manifest['files']) != 5:
        raise RuntimeError('Incomplete build bundle')
    return manifest


def candidate(config, image, mounts):
    config = copy.deepcopy(config)
    if set(config.get('services', {})) != {'metersphere'}:
        raise RuntimeError('Unexpected companion service')
    service = config['services']['metersphere']
    if service.get('image') != BASE_IMAGE and not service.get('image', '').startswith('metersphere-alerts:'):
        raise RuntimeError('Unexpected existing image')
    conf = [m for m in mounts if m['Destination'] == '/opt/metersphere/conf']
    if len(conf) != 1 or conf[0]['Type'] != 'volume' or not conf[0].get('Name'):
        raise RuntimeError('Expected one existing configuration volume')
    # Explicitly reuse the anonymous vendor configuration volume during recreate.
    service['volumes'] = [v for v in service.get('volumes', []) if
                          (v.get('target') if isinstance(v, dict) else v.split(':')[1]) != '/opt/metersphere/conf']
    service['volumes'].append({'type': 'volume', 'source': 'preserved_conf', 'target': '/opt/metersphere/conf'})
    config.setdefault('volumes', {})['preserved_conf'] = {'external': True, 'name': conf[0]['Name']}
    service['image'] = image
    return config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--base', required=True, type=Path)
    parser.add_argument('--project', required=True)
    parser.add_argument('--container', required=True)
    parser.add_argument('--url', default='http://127.0.0.1:8081')
    parser.add_argument('--watchdog-unit')
    args = parser.parse_args()
    os.umask(0o077)
    base = args.base.resolve(strict=True)
    for path in [args.base, base / 'compose.yaml', base / 'data', base / '.deploy.lock', base / 'backups']:
        if path.is_symlink():
            raise RuntimeError('Unsafe instance path')
    if os.geteuid() != 0:
        raise RuntimeError('Release requires the existing privileged runner')
    manifest = checked_bundle(args.bundle, args.commit)
    compose_file = base / 'compose.yaml'
    compose = ['docker', 'compose', '--project-name', args.project, '--project-directory', str(base),
               '--env-file', '/dev/null', '-f', str(compose_file)]
    image = 'metersphere-alerts:' + args.commit
    with (base / '.deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before = json.loads(run(['docker', 'inspect', args.container]))[0]
        labels = before['Config'].get('Labels') or {}
        if labels.get('com.docker.compose.project') != args.project or labels.get('com.docker.compose.service') != 'metersphere':
            raise RuntimeError('Container ownership mismatch')
        previous = compose_file.read_bytes()
        mode = compose_file.stat().st_mode & 0o777
        desired = (json.dumps(candidate(json.loads(previous), image, before['Mounts']), indent=2) + '\n').encode()
        run(compose[:-1] + ['-', 'config', '--quiet'], input=desired.decode())
        run(['docker', 'build', '--label', 'org.opencontainers.image.revision=' + args.commit,
             '-f', str(args.bundle / 'deploy/alerts/Dockerfile'), '-t', image, str(args.bundle)], timeout=600)
        if run(['docker', 'image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', image]) != args.commit:
            raise RuntimeError('Built image identity mismatch')
        # Start with disposable data before stopping the existing service. No
        # company configuration or network is attached to the preflight image.
        probe = 'ms-alert-preflight-' + str(os.getpid())
        probe_created = False
        try:
            run(['docker', 'run', '-d', '--name', probe, '--label', 'metersphere.alert-preflight=true',
                 '--network', 'none', '--memory', '4g', '--cpus', '2',
                 '-e', 'JAVA_TOOL_OPTIONS=-Xms128m -Xmx512m', image])
            probe_created = True
            run(['docker', 'exec', '--user', '1000', probe, 'sh', '-c',
                 'test -r /metersphere/lib/metersphere-project-management-3.6.10-lts.jar '
                 '&& test -r /metersphere/static/index.html && test -x /metersphere/static/assets'])
            deadline = time.monotonic() + 300
            while True:
                result = subprocess.run(['docker', 'exec', probe, 'wget', '-q', '-T', '3', '-O', '-',
                                         'http://127.0.0.1:8081/get-key'], capture_output=True, text=True, timeout=8)
                try:
                    ready = result.returncode == 0 and bool(json.loads(result.stdout).get('data'))
                except (ValueError, AttributeError):
                    ready = False
                if ready:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError('Isolated image startup failed; existing service was not stopped')
                time.sleep(5)
            print(json.dumps({'status': 'preflight-passed', 'commit': args.commit, 'runtimeUid': 1000}), flush=True)
        finally:
            if probe_created:
                run(['docker', 'rm', '-f', '-v', probe], timeout=100)
        backups = base / 'backups'
        backups.mkdir(mode=0o700, exist_ok=True)
        size = sum(p.stat().st_size for p in (base / 'data').rglob('*') if p.is_file())
        if shutil.disk_usage(base).free < size + 2 * 1024 ** 3:
            raise RuntimeError('Insufficient backup space')
        backup = backups / ('alerts-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()))
        backup.mkdir(mode=0o700)
        atomic(backup / 'compose.json', previous, 0o600)
        # Check archive support before stopping the service.
        run(['tar', '--version'])
        timer_active = bool(args.watchdog_unit) and subprocess.run(
            ['systemctl', 'is-active', '--quiet', args.watchdog_unit], capture_output=True).returncode == 0
        changed = False
        try:
            if timer_active:
                run(['systemctl', 'stop', args.watchdog_unit])
            run(compose + ['stop', '--timeout', '60'], timeout=100)
            conf = next(m['Source'] for m in before['Mounts'] if m['Destination'] == '/opt/metersphere/conf')
            run(['tar', '--xattrs', '--acls', '--numeric-owner', '-cpf', str(backup / 'data.tar'), '-C', str(base), 'data'], timeout=600)
            run(['tar', '--xattrs', '--acls', '--numeric-owner', '-cpf', str(backup / 'conf.tar'), '-C', conf, '.'], timeout=120)
            for name in ['data.tar', 'conf.tar']:
                run(['tar', '-tf', str(backup / name)], timeout=120)
                os.chmod(backup / name, 0o600)
            receipt = {'previousImage': before['Config']['Image'], 'previousImageId': before['Image'],
                       'newCommit': args.commit, 'createdAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                       'databaseRecovery': 'Cold data/config archives; application rollback does not restore the database.'}
            atomic(backup / 'release.json', (json.dumps(receipt) + '\n').encode(), 0o600)
            atomic(compose_file, desired, mode)
            changed = True
            run(compose + ['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '600'], timeout=650)
            after = json.loads(run(['docker', 'inspect', args.container]))[0]
            identities = lambda x: sorted((m['Type'], m['Source'], m['Destination']) for m in x['Mounts'])
            if after['Config']['Image'] != image or identities(before) != identities(after):
                raise RuntimeError('Image or volume identity mismatch')
            if after['State'].get('Health', {}).get('Status') != 'healthy':
                raise RuntimeError('Container is not healthy')
            with urllib.request.urlopen(args.url + '/get-key', timeout=10) as response:
                if not json.load(response).get('data'):
                    raise RuntimeError('Application health check failed')
            # Verify the actual served frontend and exact JAR bytes, not just a tag.
            for name, digest in manifest['files'].items():
                target = '/metersphere/lib/' + Path(name).name if name.endswith('.jar') else None
                if name == 'frontend/dist/index.html':
                    target = '/metersphere/static/index.html'
                if target and run(['docker', 'exec', args.container, 'sha256sum', target]).split()[0] != digest:
                    raise RuntimeError('Runtime build checksum mismatch')
            with urllib.request.urlopen(args.url + '/', timeout=10) as response:
                if hashlib.sha256(response.read()).hexdigest() != manifest['files']['frontend/dist/index.html']:
                    raise RuntimeError('Served frontend checksum mismatch')
            print(json.dumps({'status': 'deployed', 'commit': args.commit, 'image': image, 'imageId': after['Image'],
                              'backup': str(backup), 'volumesPreserved': True, 'health': 'healthy'}), flush=True)
        except Exception as error:
            restored = False
            try:
                if changed:
                    atomic(compose_file, previous, mode)
                run(compose + ['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '600'], timeout=650)
                restored = True
            finally:
                print(json.dumps({'status': 'failed', 'rollback': 'restored' if restored else 'failed',
                                  'backup': str(backup), 'error': str(error)[:200]}), flush=True)
            raise RuntimeError('Application release failed; see bounded rollback result') from None
        finally:
            if timer_active:
                run(['systemctl', 'start', args.watchdog_unit])


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'failed', 'error': str(error)[:200]}), flush=True)
        raise SystemExit(1)
