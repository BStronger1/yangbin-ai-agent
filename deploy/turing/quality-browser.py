"""Browser acceptance for saved source checks and historical unreviewed plans.

Uses the task created by quality-smoke.py prepare. Never creates a new task.
A warning fixture, when needed, changes only one browser GET response; the report
explicitly distinguishes that rendering check from a real model finding.
"""
from copy import deepcopy
from pathlib import Path
import json
import uuid

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[2]
LOGS = ROOT / 'logs'
SHOTS = LOGS / 'screenshots'
LEGACY_ID = '6679fd39-2fdb-4d40-9827-adbaa99a998b'
FIXTURE_CODE = 'HEALTH_RESTRICTION_UNSUPPORTED'


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    env = read_json(ROOT / 'secrets.json')['env']
    state = read_json(LOGS / 'quality-v3-state.json')
    task_id = state['id']
    assert str(uuid.UUID(task_id)) == task_id, 'Invalid acceptance task id'
    base = 'http://' + env['PUBLIC_BIND'] + ':' + env['PUBLIC_PORT']
    task_api = base + '/api/ai/tasks/' + task_id
    SHOTS.mkdir(parents=True, exist_ok=True)
    report = {'task': task_id, 'legacy_task': LEGACY_ID}
    secrets = {
        name: str(value) for name, value in env.items()
        if value and len(str(value)) >= 8
        and any(marker in name.upper() for marker in ('KEY', 'TOKEN', 'PASSWORD', 'SECRET'))
    }
    errors = []
    dom_checks = 0

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=str(Path.home() / 'apps/agent-workbench/runtime/browsers/chromium-1194/chrome-linux/chrome'),
            headless=True, args=['--no-sandbox'], timeout=120000,
        )
        try:
            context = browser.new_context(viewport={'width': 1360, 'height': 960}, timezone_id='Asia/Shanghai')
            login = context.request.post(
                base + '/api/auth/login', headers={'Origin': base},
                data={'username': env['ACCESS_USER'], 'password': env['ACCESS_PASSWORD']},
            )
            assert login.status == 200, 'Normal account login failed'
            assert login.json().get('user'), 'Login did not establish a user session'
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))

            def fetch_task(url=task_api):
                response = context.request.get(url)
                assert response.status == 200, 'Task API is unavailable to the logged-in account'
                return response.json()

            def check_dom():
                nonlocal dom_checks
                html = page.content()
                for name, secret in secrets.items():
                    assert secret not in html, 'A private configuration value appeared in DOM: ' + name
                dom_checks += 1

            def screenshot(name):
                check_dom()
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Page has horizontal overflow'
                page.screenshot(path=str(SHOTS / name), full_page=True)

            initial = fetch_task()
            assert initial['id'] == task_id, 'Acceptance task response mismatched state file'
            assert initial['status'] in {'AWAITING_CONFIRMATION', 'QUEUED', 'RUNNING', 'SUCCEEDED'}, 'Task is not ready to confirm or inspect'
            if initial['status'] in {'QUEUED', 'RUNNING'}:
                assert initial.get('confirmedAt'), 'Prepare has not reached confirmation yet; wait for quality-smoke.py prepare to finish'
            page.goto(base + '/tasks/' + task_id, wait_until='networkidle')
            status = page.get_by_test_id('task-status')
            confirmed_this_run = False
            if initial['status'] == 'AWAITING_CONFIRMATION':
                expect(status).to_have_text('等待确认')
                expect(page.get_by_test_id('execution-plan')).to_be_visible()
                expect(page.get_by_test_id('planned-step-pdf')).to_be_visible()
                assert initial.get('quality') is None, 'Waiting task unexpectedly has a completed source report'
                page.reload(wait_until='networkidle')
                expect(status).to_have_text('等待确认')
                page.get_by_role('button', name='确认并执行', exact=True).click()
                confirmed_this_run = True
            # Reruns inspect the same task; never click retry or create another paid task.
            expect(status).to_have_text('已完成', timeout=240000)
            completed = fetch_task()
            assert completed['status'] == 'SUCCEEDED', 'UI and API completion state differ'
            quality = completed.get('quality')
            assert isinstance(quality, dict), 'Completed task is missing source checks'
            assert quality['ruleVersion'] == 'grounding-v1', 'Unexpected source-check rule version'
            assert quality['status'] in {'REVIEW_REQUIRED', 'CHECKED_WITH_LIMITATIONS'}, 'Unexpected report status'
            assert completed.get('pdfUrl'), 'Acceptance plan must include PDF delivery'
            expect(page.get_by_role('link', name='下载 PDF', exact=True)).to_be_visible()
            quality_panel = page.get_by_test_id('plan-quality')
            expect(quality_panel).to_be_visible()
            expect(quality_panel).to_contain_text('grounding-v1')
            expect(quality_panel).to_contain_text('地点名称与候选引用对应')
            expected_status = '发现待核实事项' if quality['status'] == 'REVIEW_REQUIRED' else '规则检查完成，仍有未核实信息'
            expect(quality_panel).to_contain_text(expected_status)
            expect(quality_panel).to_contain_text('费用属于估算')
            expect(quality_panel).not_to_contain_text('历史方案未做来源检查')
            before_reload = quality_panel.inner_text()
            page.reload(wait_until='networkidle')
            expect(status).to_have_text('已完成')
            expect(quality_panel).to_have_text(before_reload, use_inner_text=True)
            assert fetch_task()['quality'] == quality, 'Refreshing changed the saved source report'
            screenshot('quality-v3-task-desktop.png')
            page.set_viewport_size({'width': 390, 'height': 844})
            screenshot('quality-v3-task-mobile.png')
            report.update({
                'ui_confirm_execute_this_run': confirmed_this_run,
                'existing_task_reused': True,
                'completed': True,
                'grounding_report_displayed': True,
                'completed_refresh_preserved': True,
                'pdf_link': True,
                'desktop_no_overflow': True,
                'mobile_no_overflow': True,
                'report_status': quality['status'],
                'matched_stops': quality['matchedStops'],
                'total_stops': quality['totalStops'],
                'screenshots': ['logs/screenshots/quality-v3-task-desktop.png', 'logs/screenshots/quality-v3-task-mobile.png'],
            })
            print('Source report, confirmation, refresh and responsive layout passed', flush=True)

            warnings = [item for item in (quality.get('findings') or []) if item and item.get('severity') == 'warning']
            report['real_report_warning_count'] = len(warnings)
            if warnings:
                first_code = warnings[0].get('code') or 'UNKNOWN'
                expect(page.get_by_test_id('quality-finding-' + first_code).first).to_be_visible()
                screenshot('quality-v3-warning-real.png')
                report.update({'real_warning_rendering': True, 'simulated_warning_rendering': False})
                report['screenshots'].append('logs/screenshots/quality-v3-warning-real.png')
            else:
                fixture_served = {'count': 0}

                def warning_fixture(route):
                    if route.request.method != 'GET':
                        route.continue_()
                        return
                    response = route.fetch()
                    assert response.status == 200, 'Cannot load real response for warning fixture'
                    payload = response.json()
                    assert payload['id'] == task_id, 'Warning fixture matched an unexpected task'
                    original_quality = payload.get('quality')
                    assert original_quality == quality, 'Real report changed before fixture injection'
                    fixture = deepcopy(original_quality)
                    fixture['status'] = 'REVIEW_REQUIRED'
                    fixture['findings'] = list(fixture.get('findings') or []) + [{
                        'code': FIXTURE_CODE, 'severity': 'warning', 'field': 'itinerary.cautions',
                        'message': '【合成界面验收】健康限制描述缺少来源，请核实。此项仅验证提醒样式，不代表模型产生了这条问题。',
                    }]
                    payload['quality'] = fixture
                    fixture_served['count'] += 1
                    route.fulfill(response=response, json=payload)

                # Page routing affects browser traffic only, never the server task or APIRequestContext.
                page.route(task_api, warning_fixture)
                try:
                    page.reload(wait_until='networkidle')
                    expect(status).to_have_text('已完成')
                    finding = page.get_by_test_id('quality-finding-' + FIXTURE_CODE)
                    expect(finding).to_be_visible()
                    expect(finding).to_contain_text('合成界面验收')
                    expect(quality_panel).to_contain_text('发现待核实事项')
                    assert fixture_served['count'] >= 1, 'Warning fixture was never delivered'
                    screenshot('quality-v3-warning-fixture.png')
                    assert fetch_task()['quality'] == quality, 'Warning fixture unexpectedly changed server data'
                finally:
                    page.unroute(task_api, warning_fixture)
                report.update({
                    'real_warning_rendering': False, 'simulated_warning_rendering': True,
                    'warning_fixture_only_browser': True, 'server_report_unchanged_after_fixture': True,
                    'warning_fixture_code': FIXTURE_CODE,
                    'warning_fixture_note': 'Synthetic browser rendering test only; not a real model warning or a saved task finding.',
                })
                report['screenshots'].append('logs/screenshots/quality-v3-warning-fixture.png')

            legacy = fetch_task(base + '/api/ai/tasks/' + LEGACY_ID)
            assert legacy['status'] == 'SUCCEEDED' and legacy.get('quality') is None, 'Expected an unchanged historical task without source checks'
            page.goto(base + '/tasks/' + LEGACY_ID, wait_until='networkidle')
            expect(page.get_by_test_id('task-status')).to_have_text('已完成')
            legacy_panel = page.get_by_test_id('plan-quality')
            expect(legacy_panel).to_be_visible()
            expect(legacy_panel).to_contain_text('历史方案未做来源检查')
            expect(legacy_panel).not_to_contain_text('规则检查完成')
            expect(legacy_panel).not_to_contain_text('有对应依据')
            screenshot('quality-v3-legacy-unchecked.png')
            report['screenshots'].append('logs/screenshots/quality-v3-legacy-unchecked.png')
            report['legacy_unchecked_label'] = True
            assert not errors, 'Browser JavaScript errors occurred'
            report.update({'no_secret_in_dom': True, 'dom_checks': dom_checks, 'js_errors': errors})
            (LOGS / 'quality-v3-browser.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(report, ensure_ascii=False), flush=True)
        finally:
            browser.close()


if __name__ == '__main__':
    main()
