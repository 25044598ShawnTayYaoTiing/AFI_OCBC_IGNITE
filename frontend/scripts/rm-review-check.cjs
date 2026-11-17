// Browser regression checks with captured synthetic API fixtures. No live writes.
// Usage: node frontend/scripts/rm-review-check.cjs <fixtures.json> [preview-origin] [output-dir]
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const {
  chromium,
} = require(require.resolve("playwright", {
  paths: [path.resolve(__dirname, ".."), path.resolve(__dirname, "../../../OCBC_CustomerPortal_push")],
}));
const fixtures = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const origin = process.argv[3] || "http://127.0.0.1:5185";
const out = path.resolve(process.argv[4] || ".local/rm-redesign");
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const errors = [];
  let approvals = 0;
  let rejectApproval = false;
  let roles = ["REVIEWER", "OPERATOR"];
  const data = structuredClone(fixtures);
  data.cancelled = structuredClone(data.missing);
  data.cancelled.submission.id = "DOC-CANCELLED-FIXTURE";
  data.cancelled.submission.status = "CANCELLED";
  data.cancelled.exceptions.forEach(issue => { issue.status = "CANCELLED"; });
  data.cancelled.tasks.forEach(task => { task.status = "CANCELLED"; });
  data.medium = structuredClone(data.missing);
  data.medium.submission.id = "DOC-MEDIUM-BROWSER-FIXTURE";
  data.medium.submission.status = "RESOLUTION_MANAGEMENT";
  data.medium.submission.risk_score = 35;
  data.medium.riskAssessment.score = 35;
  data.medium.tasks.forEach((task) => {
    task.assigned_role = "RESOLUTION_MANAGEMENT";
    task.status = "AWAITING_RESOLVER_AUTHORIZATION";
  });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
  });
  await context.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    let body = {};
    let status = 200;
    if (url.pathname === "/api/auth/me")
      body = { username: "reviewer", roles, demoMode: true };
    else if (
      url.pathname.includes("/versions/") &&
      url.pathname.endsWith("/pdf")
    ) {
      return route.fulfill({
        contentType: "application/pdf",
        body: fs.readFileSync(
          path.resolve(
            __dirname,
            "../../backend/src/main/resources/synthetic/customer-information.pdf",
          ),
        ),
      });
    } else if (url.pathname === "/api/documents/submissions")
      body = Object.values(data).map((item) => ({
        ...item.submission,
        open_exceptions: item.exceptions.filter(
          (issue) => issue.status === "OPEN",
        ).length,
      }));
    else if (url.pathname.endsWith("/audit")) body = [];
    else {
      const match = url.pathname.match(/\/submissions\/([^/]+)(?:\/(.+))?$/);
      const detail =
        match &&
        Object.values(data).find((item) => item.submission.id === match[1]);
      if (detail) {
        if (request.method() === "POST" && match[2] === "approve") {
          approvals++;
          assert.equal(
            request.postDataJSON().revision,
            detail.submission.revision,
          );
          await new Promise((resolve) => setTimeout(resolve, 350));
          if (rejectApproval) {
            status = 409;
            body = {
              code: "NOT_READY",
              message: "The case changed. Recheck before approval.",
            };
          } else {
            detail.submission.status = "OPERATIONS";
            detail.routing = [
              {
                destination: "OPERATIONS",
                created_at: new Date().toISOString(),
                reason: "Browser fixture approval",
              },
            ];
            body = detail;
          }
        } else body = detail;
      }
    }
    return route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
  const page = await context.newPage();
  page.on("pageerror", (error) => errors.push(error.message));
  async function open(key) {
    await page.goto(origin + "/submissions/" + data[key].submission.id);
    await page.getByLabel("Password", { exact: true }).fill("fixture-password");
    await page.getByRole("button", { name: "Sign in to workspace" }).click();
    await page
      .getByRole("heading", {
        level: 1,
        name: data[key].submission.customer_name,
        exact: true,
      })
      .waitFor();
    await page.getByRole("tab", { name: "Overview", exact: true }).waitFor();
  }
  try {
    await open("missing");
    assert.equal(
      await page
        .getByRole("button", { name: "Approve for Operations", exact: true })
        .isDisabled(),
      true,
    );
    assert.match(
      await page
        .getByRole("region", { name: "Case outcome" })
        .textContent()
        .catch(() => page.locator('[aria-label="Case outcome"]').textContent()),
      /Cannot approve/,
    );
    for (const width of [1440, 1280, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      assert(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        "Overview overflow at " + width,
      );
      await page.evaluate(() => scrollTo(0, 0));
      await page.screenshot({
        path: path.join(out, "after-overview-" + width + ".png"),
        fullPage: true,
      });
      await page
        .getByRole("tab", { name: "Issues & evidence", exact: true })
        .click();
      await page
        .getByRole("heading", {
          name: "Issues requiring attention",
          exact: true,
        })
        .waitFor();
      assert(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        "Issues overflow at " + width,
      );
      await page.evaluate(() => scrollTo(0, 0));
      await page.screenshot({
        path: path.join(out, "after-issues-" + width + ".png"),
        fullPage: true,
      });
      await page.getByRole("tab", { name: "Overview", exact: true }).click();
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page
      .getByRole("tab", { name: "Issues & evidence", exact: true })
      .click();
    await page
      .getByRole("navigation", { name: "Choose an issue" })
      .getByRole("button")
      .nth(1)
      .click();
    await page
      .getByRole("button", { name: "Inspect original evidence", exact: true })
      .first()
      .click();
    await page
      .getByRole("link", { name: "Open full PDF", exact: true })
      .waitFor();
    assert.match(
      await page.locator('iframe[title^="Original PDF"]').getAttribute("src"),
      /#page=1/,
    );
    await page.getByRole("tab", { name: "Documents", exact: true }).click();
    await page
      .getByRole("link", { name: "Open full PDF", exact: true })
      .waitFor();
    await page
      .getByLabel("Document version", { exact: true })
      .selectOption(data.missing.documents[0].version_id);
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({
      path: path.join(out, "after-documents-1440.png"),
      fullPage: true,
    });
    await page.setViewportSize({ width: 390, height: 1000 });
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      "Document overflow",
    );
    await page
      .getByRole("button", { name: "Preview PDF here", exact: true })
      .click();
    assert(await page.locator('iframe[title^="Original PDF"]').isVisible());
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({
      path: path.join(out, "after-documents-390.png"),
      fullPage: true,
    });
    await page
      .getByRole("tab", { name: "AI & automation", exact: true })
      .click();
    assert.equal(
      await page
        .getByText("Risk contributions and evidence", { exact: true })
        .isVisible(),
      false,
    );
    await page
      .getByText("Risk score, contributions and decision trace", {
        exact: true,
      })
      .click();
    await page
      .getByText("Risk contributions and evidence", { exact: true })
      .waitFor();
    await page.setViewportSize({ width: 1440, height: 1000 });
    await open("conflict");
    assert(
      await page
        .getByRole("button", { name: "Clear reviewed hold", exact: true })
        .isDisabled(),
    );
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({
      path: path.join(out, "after-conflict-1440.png"),
      fullPage: true,
    });
    await page.getByRole("tab", { name: "Documents", exact: true }).click();
    const historical = data.conflict.documents.find(
      (doc) => doc.versions.length > 1,
    );
    assert(historical, "The conflict fixture must include version history");
    await page
      .getByLabel("Document to inspect", { exact: true })
      .selectOption(historical.id);
    const oldVersion = historical.versions.find(
      (version) => version.id !== historical.version_id,
    );
    await page
      .getByLabel("Document version", { exact: true })
      .selectOption(oldVersion.id);
    await page
      .getByText("You are viewing an earlier document version.", {
        exact: false,
      })
      .waitFor();
    await page
      .getByRole("tab", { name: "Extracted fields", exact: true })
      .click();
    await page
      .getByText("Current extracted values are hidden", { exact: false })
      .waitFor();
    // Revalidation is mandatory after a package revision changes.
    data.ready.submission.validated_revision =
      data.ready.submission.revision - 1;
    await open("ready");
    assert(
      await page
        .getByRole("button", { name: "Approve for Operations", exact: true })
        .isDisabled(),
    );
    data.ready.submission.validated_revision = data.ready.submission.revision;
    await open("medium");
    const authorise = page.getByRole("button", {
      name: "Authorise customer correction",
      exact: true,
    });
    assert(await authorise.isDisabled());
    await page
      .getByLabel("Reviewer decision note", { exact: false })
      .fill("Reviewed the cited sources; customer correction is appropriate.");
    assert(await authorise.isEnabled());
    await authorise.click();
    await page
      .getByRole("dialog", { name: "Confirm reviewer decision" })
      .getByRole("button", { name: "Cancel", exact: true })
      .click();
    await open("completed");
    assert.equal(
      await page
        .getByRole("button", { name: "Approve for Operations", exact: true })
        .count(),
      0,
    );
    await page.getByRole("tab", { name: "History", exact: true }).click();
    await page
      .getByText("Sent to Operations", { exact: true })
      .first()
      .waitFor();
    await open("cancelled");
    await page.getByRole("heading", { name: "Request cancelled by the customer", exact: true }).waitFor();
    assert.equal(await page.getByRole("button", { name: "Recheck package", exact: true }).count(), 0);
    assert.equal(await page.getByRole("button", { name: "Approve for Operations", exact: true }).count(), 0);
    await open("ready");
    const approve = page.getByRole("button", {
      name: "Approve for Operations",
      exact: true,
    });
    assert.equal(await approve.isEnabled(), true);
    await approve.click();
    const dialog = page.getByRole("dialog", {
      name: "Confirm reviewer decision",
    });
    assert.equal(
      await dialog
        .getByRole("button", { name: "Cancel", exact: true })
        .evaluate((el) => el === document.activeElement),
      true,
    );
    await page.keyboard.press("Escape");
    assert.equal(approvals, 0);
    assert.equal(
      await approve.evaluate((el) => el === document.activeElement),
      true,
    );
    rejectApproval = true;
    await approve.click();
    await dialog
      .getByRole("button", { name: "Confirm decision", exact: true })
      .click();
    await page
      .getByText("The case changed. Recheck before approval.", { exact: true })
      .waitFor();
    assert.equal(approvals, 1);
    rejectApproval = false;
    await approve.click();
    const confirm = dialog.getByRole("button", {
      name: "Confirm decision",
      exact: true,
    });
    await confirm.evaluate((el) => {
      el.click();
      el.click();
    });
    await page
      .getByRole("heading", { name: "Handed over to Operations", exact: true })
      .waitFor();
    assert.equal(
      approvals,
      2,
      "Double submit must produce only one additional approval",
    );
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({
      path: path.join(out, "after-approved-1440.png"),
      fullPage: true,
    });
    roles = ["OPERATOR"];
    data.ready.submission.status = "RESOLUTION_MANAGEMENT";
    await open("ready");
    assert(
      await page
        .getByRole("button", { name: "Approve for Operations", exact: true })
        .isDisabled(),
    );
    assert.deepEqual(errors, []);
    fs.writeFileSync(
      path.join(out, "browser-results.json"),
      JSON.stringify(
        {
          passed: true,
          scope:
            "Local React UI with captured synthetic fixtures; decisions intercepted, no live mutations",
          widths: [1440, 1280, 768, 390],
          missingEvidenceBlocked: true,
          highRiskHoldBlocked: true,
          roleGuard: true,
          confirmationFocus: true,
          cancelledWithoutMutation: true,
          backendRejectionVisible: true,
          duplicateApprovalPrevented: true,
          pdfPreview: true,
          evidencePageNavigation: true,
          documentVersionHistory: true,
          staleRevisionBlocked: true,
          correctionNoteAndConfirmation: true,
          errors,
        },
        null,
        2,
      ),
    );
    console.log(
      "PASS: RM layouts, documents, policy blockers, role guards, confirmation, server rejection and duplicate prevention.",
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error.stack);
  process.exitCode = 1;
});
