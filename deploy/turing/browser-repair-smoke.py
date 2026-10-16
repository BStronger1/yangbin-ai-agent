"""Exercise the one permitted real UI repair, or inspect it with `verify`.

Run only after repair-smoke.py prepare. The existing plan is reused. This script
never submits a second repair, retries a model call, or creates another task.
A durable local marker protects against accidentally repeating an uncertain click.
"""
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlparse
import hashlib
import json
import re
import sys

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[2]
LOGS = ROOT / 'logs'
SHOTS = LOGS / 'screenshots'
PLAN_ID = 'deb8e2df-b6d2-4dba-baf8-cfcb9f7cf23c'
TERMINAL = {'REPAIRED', 'REJECTED', 'FAILED', 'INTERRUPTED', 'CONFLICT'}
TERMINAL_LABELS = re.compile(r'^(已保存纠错新版本|未采用纠错结果|纠错未完成|纠错已中断|方案版本已变化)$')


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def warn_count(quality):
    if not quality:
        return None
    return sum(item.get('severity') == 'warning' for item in (quality.get('findings') or []) if item)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'run'
    if mode not in {'run', 'verify'}:
        raise SystemExit('Expected run or verify')
    env = read_json(ROOT / 'secrets.json')['env']
    state = read_json(LOGS / 'repair-v4-state.json')
    assert state['planId'] == PLAN_ID and state['sourceVersion'] == 1, 'Unexpected prepared repair target'
    assert state['originalPlan']['id'] == PLAN_ID, 'Prepared plan identity differs'
    base = 'http://' + env['PUBLIC_BIND'] + ':' + env['PUBLIC_PORT']
    repair_api = base + '/api/ai/plans/' + PLAN_ID + '/repair'
    marker = LOGS / 'repair-v4-browser-started.json'
    SHOTS.mkdir(parents=True, exist_ok=True)
    secrets = {
        name: str(value) for name, value in env.items()
        if value and len(str(value)) >= 8
        and any(word in name.upper() for word in ('KEY', 'TOKEN', 'PASSWORD', 'SECRET'))
    }
    report = {'planId': PLAN_ID, 'mode': mode, 'sourceVersion': 1, 'screenshots': []}
    errors = []
    post_count = {'repair': 0}
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
            assert login.status == 200 and login.json().get('user'), 'Normal account login failed'
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))

            def track_request(request):
                if request.url == repair_api and request.method == 'POST':
                    post_count['repair'] += 1

            page.on('request', track_request)

            def get_json(url):
                response = context.request.get(url)
                assert response.status == 200, 'Authenticated acceptance read failed'
                return response.json()

            def screenshot(name):
                nonlocal dom_checks
                html = page.content()
                for name_key, secret in secrets.items():
                    assert secret not in html, 'Private configuration appeared in DOM: ' + name_key
                dom_checks += 1
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Page has horizontal overflow'
                page.screenshot(path=str(SHOTS / name), full_page=True)
                report['screenshots'].append('logs/screenshots/' + name)

            initial = get_json(repair_api)
            assert initial['planId'] == PLAN_ID, 'Repair state belongs to another plan'
            if mode == 'verify':
                assert initial['status'] != 'NOT_STARTED', 'Verify mode cannot initiate the real repair'
            page.goto(base + '/planner?plan=' + PLAN_ID + '&version=1', wait_until='networkidle')
            version_select = page.get_by_role('combobox', name='方案版本', exact=True)
            expect(version_select).to_have_value('1')
            repair = page.get_by_test_id('plan-repair')
            repair_status = page.get_by_test_id('repair-status')
            expect(repair).to_be_visible()
            clicked = False
            if initial['status'] == 'NOT_STARTED':
                assert mode == 'run', 'Only run mode can initiate the repair'
                assert not marker.exists(), 'A previous UI start was recorded; inspect status with verify, do not submit again'
                assert warn_count(state['originalPlan'].get('quality')), 'Prepared source has no warning to repair'
                expect(repair_status).to_have_text('尚未纠错')
                button = repair.get_by_role('button', name='按检查报告纠错一次', exact=True)
                expect(button).to_be_enabled()
                # Record intent before clicking. Even an uncertain network outcome must not cause a second click.
                with marker.open('x', encoding='utf-8') as handle:
                    json.dump({'planId': PLAN_ID, 'sourceVersion': 1, 'recordedAt': datetime.now(timezone.utc).isoformat(), 'action': 'one UI repair click only'}, handle, ensure_ascii=False, indent=2)
                button.click()
                clicked = True
            else:
                assert initial['status'] in TERMINAL | {'RUNNING', 'PUBLISHING'}, 'Unexpected repair state'
            expect(repair_status).to_have_text(TERMINAL_LABELS, timeout=240000)
            result = get_json(repair_api)
            assert result['status'] in TERMINAL, 'Repair did not reach a terminal server state'
            assert result['attempts'] == 1, 'Expected exactly one recorded repair attempt'
            assert result['sourceVersion'] == 1, 'Repair source version differs'
            expect(repair.get_by_role('button', name='按检查报告纠错一次', exact=True)).to_have_count(0)
            report.update({
                'ui_repair_clicked_this_run': clicked,
                'status': result['status'],
                'attempts': result['attempts'],
                'before_warnings': warn_count(result.get('beforeQuality')),
                'after_warnings': warn_count(result.get('afterQuality')),
                'terminal_no_retry_button': True,
            })
            if result['status'] == 'REPAIRED':
                target = result['targetVersion']
                assert target == 2, 'Expected the single new version to be version 2'
                if clicked:
                    expect(version_select).to_have_value(str(target), timeout=30000)
                    report['planner_auto_selected_target'] = True
                else:
                    repair.get_by_role('link', name='打开纠错新版本', exact=True).click()
                    expect(version_select).to_have_value(str(target))
                    report['planner_auto_selected_target'] = None
                expect(repair_status).to_have_text('已保存纠错新版本')
                for field, testid in (('beforeItinerary', 'repair-before'), ('afterItinerary', 'repair-after')):
                    assert result.get(field), 'Repaired result lacks comparison content'
                    section = repair.get_by_test_id(testid)
                    expect(section).to_be_visible()
                    if section.get_attribute('open') is None:
                        section.locator('summary').first.click()
                    expect(section).to_contain_text(result[field]['title'])
                    expect(section.get_by_test_id('plan-quality')).to_contain_text('grounding-v2')
                screenshot('repair-v4-comparison-desktop.png')
                page.set_viewport_size({'width': 390, 'height': 844})
                screenshot('repair-v4-comparison-mobile.png')
                report.update({'before_after_displayed': True, 'desktop_no_overflow': True, 'mobile_no_overflow': True})

                # Selecting the old version must retain its original saved report.
                version_select.select_option('1')
                expect(version_select).to_have_value('1')
                expect(repair_status).to_have_text('已保存纠错新版本')
                assert parse_qs(urlparse(page.url).query).get('version') == ['1'], 'Old version URL did not update'
                original_rule = state['originalPlan']['quality']['ruleVersion']
                expect(page.get_by_test_id('plan-quality').first).to_contain_text(original_rule)
                screenshot('repair-v4-original-version.png')
                repair.get_by_role('link', name='打开纠错新版本', exact=True).click()
                expect(version_select).to_have_value(str(target))
                expect(page.get_by_test_id('plan-quality').first).to_contain_text('grounding-v2')
                page.reload(wait_until='networkidle')
                expect(version_select).to_have_value(str(target))
                expect(repair_status).to_have_text('已保存纠错新版本')
                report.update({'original_new_version_switch': True, 'new_version_reload_preserved': True})

                pdf_api = base + '/api/ai/plans/' + PLAN_ID + '/' + str(target) + '/pdf'
                with page.expect_response(lambda response: response.url == pdf_api and response.request.method == 'POST', timeout=90000) as pending:
                    repair.get_by_role('button', name='导出纠错版本 PDF', exact=True).click()
                assert pending.value.status == 200, 'New-version PDF export failed'
                link = repair.get_by_role('link', name='下载纠错版本 PDF', exact=True)
                expect(link).to_be_visible()
                pdf_url = urljoin(base, link.get_attribute('href'))
                assert urlparse(pdf_url).netloc == urlparse(base).netloc, 'PDF link points outside the application'
                pdf_response = context.request.get(pdf_url)
                assert pdf_response.status == 200 and pdf_response.body().startswith(b'%PDF'), 'Exported new-version PDF is invalid'
                (LOGS / 'repair-v4-browser-plan.pdf').write_bytes(pdf_response.body())
                report.update({'new_version': target, 'independent_new_pdf_export': True})
            else:
                # Failed/rejected attempts are valid negative results; do not invent a saved new version.
                expect(version_select).to_have_value('1')
                expect(repair.get_by_role('link', name='打开纠错新版本', exact=True)).to_have_count(0)
                expect(repair.get_by_role('button', name='导出纠错版本 PDF', exact=True)).to_have_count(0)
                for field, testid in (('beforeItinerary', 'repair-before'), ('afterItinerary', 'repair-after')):
                    if result.get(field):
                        section = repair.get_by_test_id(testid)
                        expect(section).to_be_visible()
                        section.locator('summary').first.click()
                        expect(section).to_contain_text(result[field]['title'])
                screenshot('repair-v4-terminal-desktop.png')
                page.set_viewport_size({'width': 390, 'height': 844})
                screenshot('repair-v4-terminal-mobile.png')
                report.update({'new_version': None, 'independent_new_pdf_export': False, 'negative_outcome_preserved': True, 'desktop_no_overflow': True, 'mobile_no_overflow': True})

            plans = get_json(base + '/api/ai/plans/' + PLAN_ID)
            original = next(plan for plan in plans if plan['version'] == 1)
            assert original == state['originalPlan'], 'The original plan was modified'
            task_id = state['originalTask']['id']
            task = get_json(base + '/api/ai/tasks/' + task_id)
            assert task == state['originalTask'], 'Repair changed the original task snapshot'
            old_pdf = context.request.get(urljoin(base, task['pdfUrl']))
            assert old_pdf.status == 200, 'Original PDF is no longer accessible'
            assert hashlib.sha256(old_pdf.body()).hexdigest() == state['originalPdfSha256'], 'Original PDF bytes changed'
            page.goto(base + '/tasks/' + task_id, wait_until='networkidle')
            expect(page.get_by_test_id('task-status')).to_have_text('已完成')
            original_link = page.get_by_role('link', name='下载 PDF', exact=True)
            expect(original_link).to_be_visible()
            assert urljoin(base, original_link.get_attribute('href')) == urljoin(base, state['originalTask']['pdfUrl']), 'Task download link was replaced by the new PDF'
            screenshot('repair-v4-original-task.png')
            assert post_count['repair'] == (1 if clicked else 0), 'Unexpected duplicate repair POST'
            if mode == 'verify':
                assert post_count['repair'] == 0, 'Verify mode submitted a repair'
            assert not errors, 'Browser JavaScript errors occurred'
            report.update({
                'repair_posts_this_run': post_count['repair'],
                'no_automatic_repair_retry': True,
                'original_plan_unchanged': True,
                'original_task_unchanged': True,
                'original_pdf_unchanged': True,
                'original_task_pdf_link_unchanged': True,
                'no_secret_in_dom': True,
                'dom_checks': dom_checks,
                'js_errors': errors,
            })
            (LOGS / 'repair-v4-browser.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps(report, ensure_ascii=False), flush=True)
        finally:
            browser.close()


if __name__ == '__main__':
    main()
