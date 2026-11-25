#!/usr/bin/env python3
"""Run only against containers created here. Uses the fresh image's public synthetic admin account."""
import argparse
import http.cookiejar
import json
import pathlib
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()


def wait_for(fn, seconds=90):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        value = fn()
        if value:
            return value
        time.sleep(1)
    raise AssertionError('Timed out waiting for isolated acceptance condition')


class Client:
    def __init__(self, base):
        self.base = base
        self.headers = {'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest', 'Accept-Language': 'en-US'}
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def api(self, path, data=None, multipart=False):
        headers = dict(self.headers)
        payload = None
        if data is not None:
            payload = json.dumps(data).encode()
            if multipart:
                boundary = 'acceptance' + uuid.uuid4().hex
                headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
                payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="request"\r\nContent-Type: application/json\r\n\r\n'
                           + json.dumps(data) + f'\r\n--{boundary}--\r\n').encode()
        try:
            response = self.http.open(urllib.request.Request(self.base + path, data=payload, headers=headers), timeout=15)
            result = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'{path}: HTTP {error.code}') from None
        if isinstance(result, dict) and 'code' in result:
            if result['code'] != 100200:
                # Do not dump arbitrary login/session/result data into CI logs.
                message = re.sub(r'(?i)(token|password|sessionId|csrfToken)[=: ]+[^ ,;]+', r'\1=[redacted]', str(result.get('message', '')))
                raise RuntimeError(f'{path}: application code {result["code"]}: {message[:200]}')
            return result.get('data')
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', required=True)
    args = parser.parse_args()
    run = 'ms-alert-' + uuid.uuid4().hex[:10]
    app, sink = run + '-app', run + '-sink'
    print('Creating isolated acceptance containers', flush=True)
    with tempfile.TemporaryDirectory(prefix=run) as temporary:
        try:
            subprocess.run(['javac', '--release', '21', '--add-modules', 'jdk.httpserver', '-d', temporary,
                            'deploy/alerts/AlertMock.java'], check=True)
            docker('network', 'create', '--internal', '--label', 'codex.verification=' + run, run)
            docker('run', '-d', '--name', sink, '--label', 'codex.verification=' + run, '--network', run,
                   '--network-alias', 'alert-sink', '--memory', '128m', '-e', 'JAVA_TOOL_OPTIONS=-Xms16m -Xmx48m',
                   '-v', temporary + ':/mock:ro', '--entrypoint', 'java', args.image,
                   '--add-modules', 'jdk.httpserver', '-cp', '/mock', 'AlertMock')
            docker('run', '-d', '--name', app, '--label', 'codex.verification=' + run, '--network', run,
                   '--memory', '4g', '--cpus', '2', '--pids-limit', '2048',
                   '-e', 'JAVA_TOOL_OPTIONS=-Xms128m -Xmx512m', args.image)
            ip = lambda name: docker('inspect', '--format', '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', name)
            client = Client('http://' + ip(app) + ':8081')
            mock = Client('http://' + ip(sink) + ':8080')
            def ready():
                try:
                    client.api('/get-key')
                    return True
                except Exception:
                    return False
            wait_for(ready, 300)
            user = client.api('/login', {'username': 'admin', 'password': 'metersphere', 'authenticate': 'LOCAL'})
            client.headers.update({'X-AUTH-TOKEN': user['sessionId'], 'CSRF-TOKEN': user['csrfToken']})
            org = client.api('/system/organization/option/all', {})[0]['id']
            project = client.api('/system/project/add', {'organizationId': org, 'name': run, 'enable': True,
                'allResourcePool': True, 'moduleIds': ['apiTest', 'testPlan'], 'userIds': ['admin']})['id']
            client.headers.update({'ORGANIZATION': org, 'PROJECT': project})
            module = client.api('/api/scenario/module/add', {'projectId': project, 'name': 'Acceptance'})
            env = client.api('/project/environment/add', {'projectId': project, 'name': 'Synthetic environment',
                'config': {'httpConfig': [{'id': uuid.uuid4().hex, 'protocol': 'HTTP', 'hostname': 'alert-sink:8080',
                    'url': 'http://alert-sink:8080', 'type': 'NONE', 'order': 0}]}}, True)['id']
            robot = client.api('/project/robot/add', {'name': 'Isolated sink', 'projectId': project,
                'platform': 'LARK', 'webhook': 'http://alert-sink:8080/notify', 'enable': True})['id']
            client.api('/notice/message/task/save', {'projectId': project, 'taskType': 'API_SCENARIO_TASK',
                'event': 'SCENARIO_EXECUTE_FAILED', 'receiverIds': ['admin'], 'robotId': robot, 'enable': True})
            policy = client.api(f'/notice/scheduled-alert/{project}/API_SCENARIO_TASK')
            policy.update(failureThreshold=2, repeatNotifications=False,
                failureTemplate='ALERT|${resourceName}|${consecutiveFailures}|${threshold}\n${failureDetails}')
            client.api('/notice/scheduled-alert', policy)
            pool = client.api('/test/resource/pool/page', {'current': 1, 'pageSize': 20})['list'][0]
            dto = pool['testResourceDTO']
            dto['nodesList'][0]['ip'] = '127.0.0.1'
            client.api('/test/resource/pool/update', {'id': pool['id'], 'name': pool['name'], 'type': pool['type'],
                'enable': True, 'allOrg': True, 'testResourceDTO': dto})
            client.api('/project/application/update/api', {'projectId': project, 'type': 'API_RESOURCE_POOL_ID', 'typeValue': pool['id']})
            step = uuid.uuid4().hex
            request = {'polymorphicName': 'MsHTTPElement', 'name': 'Synthetic health API', 'enable': True,
                'method': 'GET', 'path': '/check?token=synthetic-secret', 'children': [{
                    'polymorphicName': 'MsCommonElement', 'enable': True, 'assertionConfig': {'enableGlobal': False,
                    'assertions': [{'assertionType': 'RESPONSE_CODE', 'enable': True, 'name': 'Expected HTTP 200',
                                    'condition': 'EQUALS', 'expectedValue': '200'}]}}]}
            scenario = client.api('/api/scenario/add', {'projectId': project, 'moduleId': module,
                'name': 'Scheduled acceptance', 'priority': 'P0', 'status': 'COMPLETED', 'environmentId': env,
                'steps': [{'id': step, 'projectId': project, 'name': 'Synthetic health API', 'stepType': 'CUSTOM_REQUEST',
                           'refType': 'DIRECT', 'enable': True, 'isNew': True}], 'stepDetails': {step: request}})['id']
            schedule = {'scenarioId': scenario, 'enable': False, 'cron': '0/15 * * * * ?',
                        'config': {'environmentId': env, 'grouped': False, 'poolId': pool['id']}}
            client.api('/api/scenario/schedule-config', schedule)
            def reports():
                return client.api('/api/report/scenario/page', {'projectId': project, 'current': 1, 'pageSize': 100})['list']
            def notifications():
                return [item.get('content', {}).get('text', '') for item in mock.api('/notifications')]
            def run_round(status, expected_notifications):
                mock.api('/mode/' + str(status))
                before = {item['id'] for item in reports()}
                schedule['enable'] = True
                client.api('/api/scenario/schedule-config', schedule)
                try:
                    report = wait_for(lambda: next((item for item in reports() if item['id'] not in before), None), 75)
                finally:
                    schedule['enable'] = False
                    client.api('/api/scenario/schedule-config', schedule)
                detail = wait_for(lambda: (item if (item := client.api('/api/report/scenario/get/' + report['id']))
                                          .get('execStatus') == 'COMPLETED' else None), 30)
                assert detail['status'] == ('SUCCESS' if status == 200 else 'ERROR'), 'Unexpected execution status'
                if expected_notifications:
                    wait_for(lambda: len(notifications()) >= expected_notifications, 15)
                time.sleep(2)
                assert len(notifications()) == expected_notifications, 'Unexpected notification count'
                print(f'Completed HTTP {status} round; notifications={expected_notifications}', flush=True)
            run_round(503, 0)
            run_round(503, 1)
            text = notifications()[0]
            for expected in ['ALERT|Scheduled acceptance|2|2', 'Synthetic health API', 'GET', '/check', '503', '200']:
                assert expected in text, 'Missing request detail: ' + expected
            assert 'synthetic-secret' not in text and '${' not in text, 'Unsafe or unresolved template data'
            run_round(503, 1)
            run_round(200, 1)  # Success notification is disabled; recovery must still reset the streak.
            run_round(503, 1)
            run_round(503, 2)
            print('Checking scheduled test-plan aggregation', flush=True)
            client.api('/notice/message/task/save', {'projectId': project, 'taskType': 'API_SCENARIO_TASK',
                'event': 'SCENARIO_EXECUTE_FAILED', 'receiverIds': ['admin'], 'robotId': robot, 'enable': False})
            client.api('/notice/message/task/save', {'projectId': project, 'taskType': 'TEST_PLAN_TASK',
                'event': 'EXECUTE_FAILED', 'receiverIds': ['admin'], 'robotId': robot, 'enable': True})
            plan_policy = client.api(f'/notice/scheduled-alert/{project}/TEST_PLAN_TASK')
            plan_policy.update(failureThreshold=2, repeatNotifications=False,
                failureTemplate='PLAN|${resourceName}|${consecutiveFailures}|${threshold}\n${failureDetails}')
            client.api('/notice/scheduled-alert', plan_policy)
            plan = client.api('/test-plan/add', {'projectId': project, 'name': 'Scheduled plan acceptance',
                'type': 'TEST_PLAN', 'passThreshold': 100})['id']
            tree = client.api('/test-plan/mind/data/' + plan)
            edits = []
            for root in tree:
                for category in root.get('children', []):
                    for level, nodes in [(1, [category]), (2, category.get('children', []))]:
                        for index, node in enumerate(nodes):
                            data = dict(node['data'])
                            data.update(level=level, num=index, extended=False, environmentId=env, grouped=False,
                                testResourcePoolId=pool['id'], retryOnFail=False, retryType='STEP', retryTimes=0,
                                retryInterval=0, stopOnFail=False, executeMethod='SERIAL', tempCollectionNode=False)
                            if level == 2 and data.get('type') == 'SCENARIO':
                                data['associateDTOS'] = [{'associateType': 'API_SCENARIO', 'projectId': project,
                                    'moduleMaps': {module: {'selectAll': False, 'selectIds': [scenario]}}}]
                            edits.append(data)
            assert any(item.get('associateDTOS') for item in edits), 'No scenario collection found'
            client.api('/test-plan/mind/data/edit', {'planId': plan, 'editList': edits})
            plan_schedule = {'resourceId': plan, 'enable': False, 'cron': '0/15 * * * * ?', 'runConfig': {'runMode': 'SERIAL'}}
            client.api('/test-plan/schedule-config', plan_schedule)
            def plan_reports():
                return client.api('/test-plan/report/page', {'projectId': project, 'current': 1, 'pageSize': 100})['list']
            def plan_round(status, expected_notifications):
                mock.api('/mode/' + str(status))
                before = {item['id'] for item in plan_reports()}
                plan_schedule['enable'] = True
                client.api('/test-plan/schedule-config', plan_schedule)
                try:
                    report = wait_for(lambda: next((item for item in plan_reports()
                        if item['id'] not in before and item.get('execStatus') == 'COMPLETED'), None), 90)
                finally:
                    plan_schedule['enable'] = False
                    client.api('/test-plan/schedule-config', plan_schedule)
                assert (report['resultStatus'] == 'SUCCESS') == (status == 200), 'Unexpected plan result'
                wait_for(lambda: len(notifications()) >= expected_notifications, 15)
                time.sleep(2)
                assert len(notifications()) == expected_notifications, 'Unexpected plan notification count'
                print(f'Completed plan HTTP {status} round; notifications={expected_notifications}', flush=True)
            plan_round(503, 2)
            plan_round(503, 3)
            text = notifications()[-1]
            for expected in ['PLAN|Scheduled plan acceptance|2|2', 'Synthetic health API', '/check', '503']:
                assert expected in text, 'Missing plan failure detail: ' + expected
            plan_round(200, 3)
            plan_round(503, 3)
            plan_round(503, 4)
            # Ensure the image serves the rebuilt frontend, rather than silently retaining the original bundle.
            actual = urllib.request.urlopen(client.base + '/', timeout=15).read().decode()
            expected = pathlib.Path('frontend/dist/index.html').read_text()
            assets = re.findall(r'assets/[^"\s]+\.js', expected)
            assert assets and assets[0] in actual, 'Rebuilt frontend is not served by the image'
            print('PASS: threshold, once-until-recovery, custom failed-request content, secret omission and frontend assets', flush=True)
        finally:
            for name in [app, sink]:
                subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(['docker', 'network', 'rm', run], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
