// Optional UI integration check. Runs only against FinanceIntegrationTest's synthetic H2 database.
const { chromium } = require(
  process.env.SAI_PLAYWRIGHT_PATH || "playwright-core",
);
const assert = require("node:assert/strict");
const fs = require("node:fs");
(async () => {
  const browser = await chromium.launch({
    executablePath: process.env.SAI_CHROMIUM || "/usr/bin/chromium",
    args: ["--no-sandbox"],
  });
  try {
    const page = await browser.newPage({
        viewport: { width: 1440, height: 1000 },
        hasTouch: true,
      }),
      errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    page.on("dialog", (d) =>
      d.type() === "confirm" ? d.accept() : d.dismiss(),
    );
    const out = process.env.SAI_SMOKE_OUTPUT || "/tmp/sai-browser-smoke";
    fs.mkdirSync(out, { recursive: true });
    await page.goto("http://127.0.0.1:8765");
    await page.locator("#loginform input[name=id]").fill("web-admin-test");
    await page.locator("#loginform input[name=password]").fill("1234");
    await page.locator("#loginform button").click();
    await page.locator("#app").waitFor({ state: "visible" });
    await page.locator("#from").fill("2044-01-01");
    await page.locator("#to").fill("2044-12-31");
    await page.locator("#refresh").click();
    await page.waitForFunction(() =>
      document.querySelector(".cards").textContent.includes("100,000"),
    );
    assert.equal(await page.locator(".cards .card").count(), 8);
    for (const kind of ["bar", "line", "mixed", "stack", "pie", "donut"]) {
      await page.locator("[data-chart-kind]").first().selectOption(kind);
      assert.equal(await page.locator(".chart svg").first().count(), 1);
    }
    await page.locator("[data-values]").first().check();
    await page.locator("#grain").selectOption("quarter");
    await page.locator("#cumulative").check();
    await page.screenshot({ path: out + "/dashboard.png", fullPage: true });
    await page.getByRole("button", { name: "헌금자", exact: true }).click();
    await page.locator('[data-pledge-contact="9003"]').click();
    await page.locator('#editForm [name=amount]').fill('100000');
    await page.locator('#editForm [name=start]').fill('2044-01');
    assert.equal(await page.locator('#editForm [name=end]').evaluate(e=>e.required),false);
    await page.locator('#editForm [name=end]').fill('');
    assert.equal(await page.locator('#editForm').evaluate(f=>f.checkValidity()),true);
    await page.locator('#editForm button').click();
    await page.waitForFunction(()=>[...document.querySelectorAll('#contactsTable tr')].some(r=>r.textContent.includes('검증 개인 이름')&&r.textContent.includes('1,200,000')&&r.textContent.includes('8.3%')));
    assert.equal(await page.evaluate(()=>data.pledgeCommitments.find(p=>p.CONTACT_ID===9003).END_MONTH),null);
    await page.locator('[data-pledge-contact="9003"]').click();
    await page.locator('[data-edit-pledge]').click();
    await page.locator('#editForm [name=end]').fill('2044-12');
    await page.locator('#editForm button').click();
    await page.waitForFunction(()=>!document.querySelector('#modal').open && data.pledgeCommitments.find(p=>p.CONTACT_ID===9003).END_MONTH==='2044-12-01');
    await page.locator('[data-pledge-contact="9003"]').click();
    await page.locator('[data-edit-pledge]').click();
    await page.locator('#editForm [name=end]').fill('');
    await page.locator('#editForm button').click();
    await page.waitForFunction(()=>!document.querySelector('#modal').open && data.pledgeCommitments.find(p=>p.CONTACT_ID===9003).END_MONTH===null);
    assert.equal(await page.evaluate(()=>data.pledgeCommitments.find(p=>p.CONTACT_ID===9003).END_MONTH),null);
    assert.equal(await page.locator('#contactsTable tr').filter({hasText:'검증 개인 이름'}).locator('.pledge-missing').textContent(),'약정헌금 미납');
    await page.locator('#to').fill('2044-01-31');await page.locator('#refresh').click();
    await page.waitForFunction(()=>document.querySelector('#contactsTable') && ![...document.querySelectorAll('#contactsTable tr')].find(r=>r.textContent.includes('검증 개인 이름')).querySelector('.pledge-missing'));
    await page.locator('#to').fill('2044-02-29');await page.locator('#refresh').click();
    await page.waitForFunction(()=>document.querySelector('#contactsTable') && [...document.querySelectorAll('#contactsTable tr')].find(r=>r.textContent.includes('검증 개인 이름')).querySelector('.pledge-missing'));
    await page.locator('#to').fill('2044-12-31');await page.locator('#refresh').click();
    await page.waitForFunction(()=>pledgeStats.to==='2044-12-31');
    await page.getByRole('button',{name:'대시보드',exact:true}).click();
    assert.ok(await page.locator('[data-series-key="pledgeTarget"]').count()>0);
    assert.ok(await page.locator('[data-series-key="full"]').count()>0);
    const catalogue=await page.evaluate(()=>report.chartCategories);
    for(const c of catalogue) assert.ok(await page.locator('svg [data-series-key="'+c.key+'"][fill="'+c.color+'"]').count()>0);
    await page.screenshot({path:out+'/pledge-dashboard.png',fullPage:true});
    await page.getByRole("button", { name: "헌금자", exact: true }).click();
    await page.locator("[data-new-contact]").click();
    await page.locator("#editForm [name=name]").fill("브라우저 신규 헌금자");
    await page.locator("#editForm button").click();
    await page
      .locator("#contactsTable tr")
      .filter({ hasText: "브라우저 신규 헌금자" })
      .locator("[data-contact]")
      .click();
    await page.locator("#editForm [name=name]").fill("브라우저 수정 헌금자");
    await page.locator("#editForm [name=active]").selectOption("false");
    await page.locator("#editForm button").click();
    await page.waitForFunction(() =>
      [...document.querySelectorAll("#contactsTable tr")].some(
        (r) =>
          r.textContent.includes("브라우저 수정 헌금자") &&
          r.textContent.includes("비활성"),
      ),
    );
    await page.getByRole("button", { name: "헌금 관리", exact: true }).click();
    assert.match(await page.locator("#content").textContent(), /100,000/);
    const giftTypes = await page
      .locator("#giftType option")
      .evaluateAll((nodes) =>
        nodes.map((n) => ({ value: n.value, label: n.textContent })),
      );
    await page
      .locator("#giftType")
      .selectOption(giftTypes.find((t) => t.label === "약정헌금").value);
    assert.match(await page.locator("#content").textContent(), /100,000/);
    await page
      .locator("#giftType")
      .selectOption(giftTypes.find((t) => t.label === "감사헌금").value);
    assert.match(
      await page.locator("#content").textContent(),
      /현재 기간·헌금자·종류에 맞는 헌금이 없습니다/,
    );
    await page.locator("[data-reset-gift-filters]").click();
    await page.waitForFunction(
      () =>
        document.querySelector("#giftType").value === "" &&
        document.querySelector("#content").textContent.includes("100,000"),
    );

    await page.locator("[data-new-gift]").click();
    const form = page.locator("#editForm");
    await form.locator("[name=year]").selectOption("9000");
    await form.locator("[name=date]").fill("2044-01-06");
    await form.locator("[name=amount]").fill("25000");
    await form.locator("[name=budget]").selectOption("9001");
    await form.locator("[name=donorName]").fill("검증 개인 이름");

    await form.locator("[name=memo]").fill("브라우저 합성 헌금");
    await form.locator("button").click();
    await page.waitForFunction(() =>
      document.querySelector("#content").textContent.includes("125,000"),
    );
    await page
      .locator("#content tr")
      .filter({ hasText: "25,000" })
      .last()
      .locator("[data-record]")
      .click();
    assert.match(await page.locator("#modal").textContent(), /25,000/);
    await page.locator("#close").click();
    await page.evaluate(async () => {
      await api("/api/write", {
        operation: "record",
        kind: "INCOME",
        year: "9000",
        date: "2044-01-07",
        amount: "15000",
        budget: "9001",
        contacts: ["9003"],
        asset: data.accounts.find((a) => a.CODE === "1100").ID,
        counterpart: data.accounts.find((a) => a.CODE === "4000").ID,
        offering: data.types[0].ID,
        memo: "합성 다중 삭제",
      });
      await load();
    });
    await page
      .locator("#content tr")
      .filter({ hasText: "25,000" })
      .last()
      .locator("[data-record-select]")
      .check();
    await page
      .locator("#content tr")
      .filter({ hasText: "15,000" })
      .last()
      .locator("[data-record-select]")
      .check();
    assert.equal(
      await page.locator("#selectionCount").textContent(),
      "2건 선택",
    );
    await page.locator("[data-cancel-selected]").click();
    await page.waitForFunction(
      () =>
        !document.querySelector("#modal").open &&
        document.querySelector("#content").textContent.includes("100,000"),
    );
    await page.evaluate(async()=>{await api('/api/write',{operation:'record',kind:'INCOME',year:9000,budget:9001,date:'2044-01-10',amount:50000,contact:9003,memo:'09:00:00 · 은행후원명',remarks:'외부 기존 메모'});await load();});
    await page.getByRole('button',{name:'외부 교회',exact:true}).click();
    await page.locator('[data-new-external]').click();
    await page.locator('#editForm [name=name]').fill('합성외부교회');
    await page.locator('#editForm [name=aliases]').fill('은행후원명');
    await page.locator('#editForm button').click();
    await page.waitForFunction(()=>document.querySelector('#content').textContent.includes('합성외부교회')&&document.querySelector('#content').textContent.includes('외부 기존 메모 · 외부교회헌금'));
    assert.equal(await page.evaluate(()=>report.pledges.actual),'100000');
    await page.screenshot({path:out+'/external-churches.png',fullPage:true});
    await page.getByRole('button',{name:'대시보드',exact:true}).click();
    const externalKey=await page.evaluate(()=>report.chartCategories.find(c=>c.label==='외부교회헌금').key);
    assert.ok(await page.locator('svg [data-series-key="'+externalKey+'"][fill="#B05A2A"]').count()>0);
    await page
      .getByRole("button", { name: "재무상태 보고서", exact: true })
      .click();
    await page.locator("#detail").selectOption("full");
    await page.pdf({
      path: out + "/report.pdf",
      format: "A4",
      printBackground: true,
    });
    assert.ok(fs.statSync(out + "/report.pdf").size > 5000);
    await page.setViewportSize({ width: 768, height: 1024 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
      true,
    );
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
      true,
    );
    await page.locator('[data-nav="대시보드"]').tap();
    await page.locator(".cards [data-metric=assets]").tap();
    assert.equal(await page.locator("#heading").textContent(), "재무상태표");
    await page.locator('[data-nav="재무상태 보고서"]').tap();
    await page.screenshot({ path: out + "/mobile.png", fullPage: true });
    await page.locator("#audience").selectOption("external");
    await page.waitForFunction(() =>
      document.querySelector("#scope").textContent.includes("공개용"),
    );
    assert.ok(
      !(await page.locator("#content").textContent()).includes(
        "검증 개인 이름",
      ),
    );
    await page.screenshot({
      path: out + "/public-preview.png",
      fullPage: true,
    });
    await page.locator("#audience").selectOption("internal");
    await page.waitForFunction(() =>
      document.querySelector('[data-nav="외부 조회"]'),
    );
    await page.getByRole("button", { name: "외부 조회", exact: true }).click();
    await page.locator("[data-share=true]").click();
    await page.waitForFunction(() =>
      document.querySelector("#shareResult").textContent.includes("공개 ON"),
    );
    const share = await page.evaluate(
      async () => await (await fetch("/api/share")).json(),
    );
    const publicPage = await browser.newPage({
      viewport: { width: 390, height: 844 },
    });
    const publicRequests = [];
    publicPage.on("request", (r) => publicRequests.push(r.url()));
    await publicPage.goto(
      "http://127.0.0.1:8766" + new URL(share.url).pathname,
    );
    await publicPage.locator("#app").waitFor({ state: "visible" });
    assert.ok(
      !publicRequests.some(
        (u) => u.includes("/api/data") || u.includes("/api/donations"),
      ),
    );
    assert.equal(await publicPage.locator("[data-new-record]").count(), 0);
    assert.ok(!(await publicPage.locator('#content').textContent()).includes('합성외부교회'));
    assert.equal(await publicPage.locator('[data-nav="외부 교회"]').count(),0);
    await page.locator("[data-share=false]").click();
    await page.waitForFunction(
      () => document.querySelector("#shareResult").textContent === "공개 OFF",
    );
    const response = await publicPage.reload();
    assert.equal(response.status(), 404);
    assert.deepEqual(errors, []);
    console.log(
      "Browser smoke passed: four-character login, gift/reversal, monthly unpaid badges, external church linkage, category colors, pledge lines, PDF, mobile, and public projection.",
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
