#!/usr/bin/env python3
"""Real Chromium acceptance for the local RAG Study Helper UI."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import time
from pathlib import Path
from typing import Any

from playwright.async_api import Error as PlaywrightError
from playwright.async_api import Page, Route, async_playwright, expect


ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "target" / "browser-acceptance"
UPLOAD_FIXTURE = ROOT / "src" / "test" / "resources" / "evaluation" / "documents" / "rag-foundations.md"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


async def select_evaluation_space(page: Page) -> str:
    options = page.locator("#spaceList .rail-item-main").filter(has_text="Acceptance Eval")
    await expect(options.first).to_be_visible(timeout=30_000)
    await options.first.click()
    name = (await page.locator("#currentSpaceName").text_content() or "").strip()
    require(name.startswith("Acceptance Eval"), f"unexpected evaluation space: {name}")
    await expect(page.locator("#documentCountBadge")).to_have_text("5", timeout=15_000)
    return name


async def wait_ready(page: Page, base_url: str) -> None:
    await page.goto(f"{base_url}/?browser-acceptance={int(time.time() * 1000)}", wait_until="domcontentloaded")
    await expect(page.locator("#runtimeText")).to_have_text("服务与依赖均已就绪", timeout=45_000)
    await expect(page.locator("#providerBadge")).to_contain_text("Mock", timeout=15_000)


async def viewport_evidence(page: Page, name: str, width: int, height: int) -> dict[str, Any]:
    await page.set_viewport_size({"width": width, "height": height})
    await page.wait_for_timeout(250)
    layout = await page.evaluate(
        """() => {
          const visible = (element) => {
            const rect = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
          };
          const tiny = [...document.querySelectorAll('button, a, input, textarea, select')]
            .filter(visible)
            .map((element) => {
              const rect = element.getBoundingClientRect();
              return {label: element.getAttribute('aria-label') || element.textContent.trim().slice(0, 40),
                width: Math.round(rect.width), height: Math.round(rect.height)};
            })
            .filter((item) => item.width < 28 || item.height < 28);
          const composer = document.querySelector('.composer-wrap')?.getBoundingClientRect();
          const latestAnswer = document.querySelector('.message.assistant:last-child')?.getBoundingClientRect();
          const composerOverlap = Boolean(composer && latestAnswer
            && Math.min(composer.bottom, latestAnswer.bottom) - Math.max(composer.top, latestAnswer.top) > 1
            && Math.min(composer.right, latestAnswer.right) - Math.max(composer.left, latestAnswer.left) > 1);
          return {
            viewportWidth: innerWidth,
            documentWidth: document.documentElement.scrollWidth,
            bodyWidth: document.body.scrollWidth,
            tinyTargets: tiny,
            composerOverlap
          };
        }"""
    )
    require(layout["documentWidth"] <= layout["viewportWidth"], f"horizontal overflow at {name}: {layout}")
    require(layout["bodyWidth"] <= layout["viewportWidth"], f"body overflow at {name}: {layout}")
    require(not layout["tinyTargets"], f"tiny interactive targets at {name}: {layout['tinyTargets']}")
    require(not layout["composerOverlap"], f"composer overlaps the latest answer at {name}: {layout}")
    await page.screenshot(path=EVIDENCE / f"{name}.png", full_page=True)
    return layout


async def happy_path(base_url: str) -> dict[str, Any]:
    console_errors: list[str] = []
    page_errors: list[str] = []
    external_requests: list[str] = []
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True)
        context = await browser.new_context(viewport={"width": 1440, "height": 900}, color_scheme="light")
        page = await context.new_page()

        def record_console(message: Any) -> None:
            if message.type == "error":
                console_errors.append(f"{message.text} @ {message.location.get('url', '')}")

        page.on("console", record_console)
        page.on("pageerror", lambda error: page_errors.append(str(error)))
        page.on("request", lambda request: external_requests.append(request.url)
                if not request.url.startswith(base_url) else None)

        await wait_ready(page, base_url)
        root_response = await context.request.get(f"{base_url}/")
        require(root_response.status == 200, f"root returned {root_response.status}")
        csp = root_response.headers.get("content-security-policy", "")
        require("frame-ancestors 'none'" in csp, f"missing response CSP: {csp}")
        require(root_response.headers.get("x-content-type-options") == "nosniff", "missing nosniff")
        favicon = await context.request.get(f"{base_url}/favicon.ico")
        require(favicon.status == 204, f"favicon returned {favicon.status}")

        selected_space = await select_evaluation_space(page)
        await page.get_by_role("tab", name="资料与分块").click()
        await expect(page.locator(".document-card")).to_have_count(5)
        vector_card = page.locator(".document-card").filter(has_text="vector-stores.md")
        await vector_card.get_by_role("button", name="检查分块").click()
        await expect(page.locator("#chunkDrawer")).to_have_attribute("aria-hidden", "false")
        await expect(page.locator("#chunkDrawerBody")).to_contain_text("向量存储与索引维护")
        await page.locator("#chunkDrawerBody").get_by_role("button", name="查看完整分块").first.click()
        await expect(page.locator("#chunkDrawerBody")).to_contain_text("字符 0–376")
        await page.keyboard.press("Escape")
        await expect(page.locator("#chunkDrawer")).to_have_attribute("aria-hidden", "true")

        await page.get_by_role("tab", name="带引用问答").click()
        question = "为什么查询命中向量后还要检查 MySQL？"
        await page.get_by_role("textbox", name="输入问题").fill(question)
        await page.get_by_role("textbox", name="输入问题").press("Enter")
        await expect(page.locator(".message.assistant .message-meta").last).to_contain_text(
            "回答已完成", timeout=30_000
        )
        await expect(page.locator(".message.assistant .message-content").last).to_contain_text("MySQL")
        citation_count = await page.locator(".message.assistant:last-child .citation-button").count()
        require(citation_count >= 1, "completed answer has no citation buttons")
        await page.locator(".message.assistant:last-child .citation-button").first.click()
        await expect(page.locator("#chunkDrawerBody")).to_contain_text("MySQL 是活跃文档版本和分块状态的真源")
        await page.keyboard.press("Escape")

        layouts: dict[str, Any] = {}
        layouts["desktop-light"] = await viewport_evidence(page, "desktop-light", 1440, 900)
        await page.locator("#themeButton").click()
        await expect(page.locator("html")).to_have_attribute("data-theme", "dark")
        layouts["desktop-dark"] = await viewport_evidence(page, "desktop-dark", 1440, 900)
        layouts["tablet-dark"] = await viewport_evidence(page, "tablet-dark", 768, 1024)
        layouts["mobile-dark"] = await viewport_evidence(page, "mobile-dark", 375, 812)
        await page.locator("#openRailButton").click()
        await expect(page.locator("#navigationRail")).to_have_class("navigation-rail open")
        await page.wait_for_timeout(250)
        rail_layout = await page.locator("#navigationRail").evaluate(
            """element => {
              const rect = element.getBoundingClientRect();
              return {
                viewportWidth: innerWidth,
                left: Math.round(rect.left),
                right: Math.round(rect.right),
                width: Math.round(rect.width)
              };
            }"""
        )
        expected_rail_width = min(310, round(rail_layout["viewportWidth"] * 0.88))
        require(abs(rail_layout["left"]) <= 1, f"mobile navigation is not fully open: {rail_layout}")
        require(rail_layout["width"] >= expected_rail_width - 1,
                f"mobile navigation is unexpectedly narrow: {rail_layout}")
        layouts["mobile-navigation"] = rail_layout
        await page.screenshot(path=EVIDENCE / "mobile-navigation.png", full_page=True)
        await page.locator("#closeRailButton").click()

        await context.set_offline(True)
        await expect(page.locator("#offlineBanner")).to_be_visible()
        before_messages = await page.locator(".message").count()
        await page.get_by_role("textbox", name="输入问题").fill("离线状态不应发请求")
        await page.get_by_role("textbox", name="输入问题").press("Enter")
        await expect(page.locator("#toastRegion")).to_contain_text("当前离线")
        require(await page.locator(".message").count() == before_messages, "offline send appended a message")
        await context.set_offline(False)
        await expect(page.locator("#offlineBanner")).to_be_hidden()

        await page.set_viewport_size({"width": 1440, "height": 900})
        slow_pattern = "**/api/spaces/*/chat"

        async def slow_stream(route: Route) -> None:
            await asyncio.sleep(3)
            try:
                await route.fulfill(
                    status=200,
                    content_type="text/event-stream",
                    body='event:status\ndata:{"requestId":"browser-slow","state":"retrieving"}\n\n'
                    'event:done\ndata:{"requestId":"browser-slow","state":"completed"}\n\n',
                )
            except PlaywrightError:
                pass

        await page.route(slow_pattern, slow_stream)
        await page.get_by_role("textbox", name="输入问题").fill("验证浏览器取消与重试")
        await page.get_by_role("textbox", name="输入问题").press("Enter")
        await expect(page.locator("#cancelStreamButton")).to_be_visible()
        await page.locator("#cancelStreamButton").click()
        await expect(page.get_by_role("button", name="重新生成")).to_be_visible(timeout=10_000)
        await expect(page.locator(".message.assistant .message-content").last).to_contain_text("已停止生成")
        await page.unroute(slow_pattern, slow_stream)
        await page.get_by_role("button", name="重新生成").click()
        await expect(page.locator(".message.assistant .message-meta").last).to_contain_text(
            "回答已完成", timeout=30_000
        )

        unique_name = f"浏览器空态 {int(time.time())}"
        await page.locator("#newSpaceButton").click()
        await page.locator("#spaceNameInput").fill(unique_name)
        await page.locator("#spaceDescriptionInput").fill("自动验收后删除")
        await page.locator("#spaceForm").get_by_role("button", name="保存").click()
        await expect(page.locator("#currentSpaceName")).to_have_text(unique_name)
        await page.get_by_role("tab", name="资料与分块").click()
        await expect(page.locator("#documentEmpty")).to_be_visible()

        upload_started = asyncio.Event()
        upload_finished = asyncio.Event()

        async def slow_upload(route: Route) -> None:
            upload_started.set()
            try:
                await asyncio.sleep(3)
                await route.abort("aborted")
            except PlaywrightError:
                pass
            finally:
                upload_finished.set()

        upload_pattern = "**/api/spaces/*/documents/upload"
        await page.route(upload_pattern, slow_upload)
        await page.locator("#fileInput").set_input_files(str(UPLOAD_FIXTURE))
        await asyncio.wait_for(upload_started.wait(), timeout=10)
        await expect(page.locator("#uploadProgress")).to_be_visible()
        await page.locator("#cancelUploadButton").click()
        await expect(page.locator("#toastRegion")).to_contain_text("上传已取消")
        # Keep interception installed until the cancelled request is settled.
        await asyncio.wait_for(upload_finished.wait(), timeout=10)
        await page.unroute(upload_pattern, slow_upload)
        await page.locator("#refreshLibraryButton").click()
        await expect(page.locator(".document-card")).to_have_count(0)
        await expect(page.locator("#documentEmpty")).to_be_visible()

        jobs_started = asyncio.Event()

        async def slow_jobs(route: Route) -> None:
            jobs_started.set()
            await asyncio.sleep(2)
            try:
                await route.continue_()
            except PlaywrightError:
                pass

        jobs_pattern = "**/api/spaces/*/jobs"
        await page.route(jobs_pattern, slow_jobs)
        await asyncio.wait_for(jobs_started.wait(), timeout=6)
        delete_button = page.get_by_role("button", name=f"删除 {unique_name}")
        await delete_button.click()
        await expect(page.locator("#confirmDialog")).to_be_visible()
        await page.locator("#confirmAcceptButton").click()
        await expect(page.locator("#confirmDialog")).not_to_be_visible()
        await expect(page.locator("#currentSpaceName")).not_to_have_text(unique_name)
        await page.unroute(jobs_pattern, slow_jobs)

        await page.wait_for_timeout(400)
        require(not console_errors, f"unexpected console errors: {console_errors}")
        require(not page_errors, f"page errors: {page_errors}")
        require(not external_requests, f"unexpected external requests: {external_requests}")
        await browser.close()
        return {
            "space": selected_space,
            "citations": citation_count,
            "layouts": layouts,
            "consoleErrors": console_errors,
            "pageErrors": page_errors,
            "externalRequests": external_requests,
        }


async def handled_failure_and_retry(base_url: str) -> dict[str, Any]:
    console_errors: list[str] = []
    page_errors: list[str] = []
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True)
        context = await browser.new_context(viewport={"width": 1440, "height": 900})
        page = await context.new_page()
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on("pageerror", lambda error: page_errors.append(str(error)))
        await wait_ready(page, base_url)
        await select_evaluation_space(page)

        async def fail_chat(route: Route) -> None:
            await route.fulfill(
                status=503,
                content_type="application/json",
                body='{"resCode":"503","msg":"Browser injected dependency failure"}',
            )

        pattern = "**/api/spaces/*/chat"
        await page.route(pattern, fail_chat)
        await page.get_by_role("textbox", name="输入问题").fill("验证失败后的显式重试")
        await page.get_by_role("textbox", name="输入问题").press("Enter")
        await expect(page.get_by_role("button", name="重试此问题")).to_be_visible(timeout=10_000)
        await expect(page.locator(".message.assistant .message-content").last).to_contain_text(
            "Browser injected dependency failure"
        )
        await page.unroute(pattern, fail_chat)
        await page.get_by_role("button", name="重试此问题").click()
        await expect(page.locator(".message.assistant .message-meta").last).to_contain_text(
            "回答已完成", timeout=30_000
        )
        require(not page_errors, f"failure flow raised page errors: {page_errors}")
        require(all("503" in message for message in console_errors),
                f"unexpected failure-flow console errors: {console_errors}")
        await browser.close()
        return {"expectedNetworkErrors": console_errors, "pageErrors": page_errors}


async def authenticated_flow(auth_url: str, api_key: str) -> dict[str, Any]:
    page_errors: list[str] = []
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True)
        context = await browser.new_context(viewport={"width": 1440, "height": 900})
        page = await context.new_page()
        page.on("pageerror", lambda error: page_errors.append(str(error)))
        await page.goto(f"{auth_url}/?auth-acceptance={int(time.time() * 1000)}", wait_until="domcontentloaded")
        await expect(page.locator("#settingsDialog")).to_be_visible(timeout=20_000)
        await page.locator("#apiKeyInput").fill(api_key)
        await page.locator("#settingsForm").get_by_role("button", name="保存并重试").click()
        await expect(page.locator("#runtimeText")).to_have_text("服务与依赖均已就绪", timeout=30_000)
        storage = await page.evaluate(
            """() => ({
              localKeys: Object.keys(localStorage),
              sessionHasKey: sessionStorage.getItem('rag-study-helper:api-key') !== null
            })"""
        )
        require(storage["sessionHasKey"], "API key was not stored for the browser session")
        require(all("api-key" not in key for key in storage["localKeys"]), "API key leaked to localStorage")
        require(not page_errors, f"authenticated flow page errors: {page_errors}")
        await browser.close()
        return {"sessionStorageOnly": True, "pageErrors": page_errors}


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("RAG_BASE_URL", "http://127.0.0.1:19050"))
    parser.add_argument("--auth-url", default=os.getenv("RAG_AUTH_URL", ""))
    parser.add_argument("--auth-key", default=os.getenv("RAG_AUTH_KEY", ""))
    args = parser.parse_args()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    results: dict[str, Any] = {
        "baseUrl": args.base_url,
        "happyPath": await happy_path(args.base_url.rstrip("/")),
        "handledFailure": await handled_failure_and_retry(args.base_url.rstrip("/")),
    }
    if args.auth_url:
        require(bool(args.auth_key), "--auth-key is required with --auth-url")
        results["authenticatedFlow"] = await authenticated_flow(args.auth_url.rstrip("/"), args.auth_key)
    summary_path = EVIDENCE / "summary.json"
    summary_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "PASS", "evidence": str(summary_path), "results": results}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
