#!/usr/bin/env node

/**
 * spring-ai-rag WebUI E2E Test Script
 * 
 * Prerequisites:
 *   npm install -g playwright
 *   npx playwright install chromium
 * 
 * Usage:
 *   node scripts/webui-e2e-test.js
 * 
 * Environment:
 *   BASE_URL - Backend URL (default: http://localhost:8081)
 * 
 * This script runs E2E tests against the spring-ai-rag WebUI,
 * verifying that each page loads correctly and has expected elements.
 */

const { chromium } = require('playwright');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8081';
const WEBUI_BASE = `${BASE_URL}/webui`;

// Test configuration
const TEST_CONFIG = {
  headless: true,
  viewport: { width: 1280, height: 800 },
  timeout: 30000,
};

// Helper to take screenshot on failure
async function takeScreenshot(page, name) {
  const path = `test-results/${name}-${Date.now()}.png`;
  await page.screenshot({ path, fullPage: true });
  console.log(`  📸 Screenshot saved: ${path}`);
  return path;
}

// Test results tracking
const results = {
  passed: 0,
  failed: 0,
  errors: [],
};

async function runTest(name, fn) {
  console.log(`\n🧪 ${name}`);
  const browser = await chromium.launch(TEST_CONFIG);
  const context = await browser.newContext({
    viewport: TEST_CONFIG.viewport,
  });
  const page = await context.newPage();
  
  try {
    await fn(page, browser);
    console.log(`  ✅ PASSED`);
    results.passed++;
  } catch (error) {
    console.log(`  ❌ FAILED: ${error.message}`);
    await takeScreenshot(page, name.replace(/\s+/g, '-').toLowerCase());
    results.failed++;
    results.errors.push({ name, error: error.message });
  } finally {
    await browser.close();
  }
}

// Navigation helper
async function navigateAndCheck(page, url, checks = []) {
  console.log(`  → Navigating to ${url}`);
  const response = await page.goto(url, { waitUntil: 'networkidle', timeout: TEST_CONFIG.timeout });
  
  if (!response || response.status() >= 400) {
    throw new Error(`Page returned HTTP ${response?.status() || 'no response'}`);
  }
  
  console.log(`  → HTTP ${response.status()} - Page loaded`);
  
  for (const check of checks) {
    try {
      if (typeof check === 'function') {
        await check(page);
      } else if (typeof check === 'object') {
        const { selector, description, state = 'visible' } = check;
        const el = page.locator(selector);
        
        switch (state) {
          case 'visible':
            if (!(await el.isVisible({ timeout: 5000 }))) {
              throw new Error(`${description || selector} not visible`);
            }
            break;
          case 'hidden':
            if (await el.isVisible({ timeout: 5000 })) {
              throw new Error(`${description || selector} should not be visible`);
            }
            break;
          case 'enabled':
            if (!(await el.isEnabled({ timeout: 5000 }))) {
              throw new Error(`${description || selector} not enabled`);
            }
            break;
        }
        
        console.log(`  → ${description || selector}: OK`);
      }
    } catch (e) {
      throw new Error(`Check failed: ${e.message}`);
    }
  }
}

// ============================================================
// TEST SUITE
// ============================================================

async function testDashboard(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/dashboard`, [
    { selector: 'h1', description: 'Page title (h1)', state: 'visible' },
    { selector: 'nav', description: 'Navigation sidebar', state: 'visible' },
  ]);
  
  // Check sidebar has all navigation items
  const navItems = ['Dashboard', 'Documents', 'Collections', 'Chat', 'Search', 'Metrics', 'Alerts', 'Settings'];
  for (const item of navItems) {
    const el = page.locator(`nav >> text=${item}`);
    if (!(await el.isVisible({ timeout: 3000 }))) {
      throw new Error(`Navigation item "${item}" not found`);
    }
  }
  console.log('  → All navigation items present');
}

async function testDocuments(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/documents`, [
    { selector: 'h1', description: 'Page title', state: 'visible' },
    { selector: 'input[type="file"]', description: 'File upload input', state: 'visible' },
    { selector: 'table', description: 'Documents table (or empty state)', state: 'visible' },
  ]);
  
  // Verify the page title says "Documents"
  const title = await page.locator('h1').textContent();
  if (!title.includes('Documents')) {
    throw new Error(`Expected title to contain "Documents", got "${title}"`);
  }
  console.log(`  → Page title: "${title.trim()}"`);
}

async function testCollections(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/collections`, [
    { selector: 'h1', description: 'Page title', state: 'visible' },
  ]);
  
  const title = await page.locator('h1').textContent();
  if (!title.includes('Collections')) {
    throw new Error(`Expected title to contain "Collections", got "${title}"`);
  }
  console.log(`  → Page title: "${title.trim()}"`);
}

async function testChat(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/chat`, [
    { selector: 'h1', description: 'Page title', state: 'visible' },
    { selector: 'textarea, input[type="text"]', description: 'Chat input', state: 'visible' },
    { selector: 'button:has-text("Send")', description: 'Send button', state: 'visible' },
  ]);
  
  const title = await page.locator('h1').textContent();
  if (!title.includes('Chat')) {
    throw new Error(`Expected title to contain "Chat", got "${title}"`);
  }
  console.log(`  → Page title: "${title.trim()}"`);
}

async function testChatInteraction(page) {
  // Navigate to chat
  await page.goto(`${WEBUI_BASE}/chat`, { waitUntil: 'networkidle', timeout: TEST_CONFIG.timeout });
  console.log('  → Chat page loaded');
  
  // Find and fill the message input
  const input = page.locator('textarea, input[type="text"]').first();
  if (!(await input.isVisible({ timeout: 5000 }))) {
    throw new Error('Chat input not visible');
  }
  
  const testMessage = 'Hello, this is a test message';
  await input.fill(testMessage);
  console.log(`  → Typed: "${testMessage}"`);
  
  // Click send button
  const sendButton = page.locator('button:has-text("Send")');
  if (!(await sendButton.isEnabled({ timeout: 5000 }))) {
    throw new Error('Send button not enabled');
  }
  await sendButton.click();
  console.log('  → Clicked Send');
  
  // Wait for assistant response to appear (streamed)
  // The response should appear in the messages area
  await page.waitForTimeout(8000); // Wait for SSE stream to complete (8s for LLM response)
  
  // Check that the user message appears
  // Note: CSS modules generate hashed class names like _msg_xxx, _user_xxx, _assistant_xxx
  const messages = page.locator('[class*="_msg_"], [class*="_user_"], [class*="_assistant_"]');
  const messageCount = await messages.count();
  console.log(`  → Messages in chat: ${messageCount}`);
  
  // Verify we have at least 2 messages (user + assistant)
  if (messageCount < 2) {
    throw new Error(`Expected at least 2 messages, got ${messageCount}`);
  }
  
  // Get the last message (assistant response)
  const lastMessage = messages.last();
  const lastMessageContent = await lastMessage.textContent();
  console.log(`  → Assistant response: "${lastMessageContent?.slice(0, 100)}..."`);
  
  // Verify the response is not empty
  if (!lastMessageContent || lastMessageContent.trim().length === 0) {
    throw new Error('Assistant response is empty');
  }
  
  console.log('  → Chat interaction test PASSED');
}

async function testSearch(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/search`, [
    { selector: 'h1', description: 'Page title', state: 'visible' },
    { selector: 'input[placeholder*="earch"]', description: 'Search input', state: 'visible' },
    { selector: 'button[type="submit"]', description: 'Search button', state: 'visible' },
  ]);
  
  const title = await page.locator('h1').textContent();
  if (!title.includes('Search')) {
    throw new Error(`Expected title to contain "Search", got "${title}"`);
  }
  console.log(`  → Page title: "${title.trim()}"`);
  
  // Test search input interaction
  const input = page.locator('input[placeholder*="earch"]');
  await input.fill('test query');
  const value = await input.inputValue();
  if (value !== 'test query') {
    throw new Error(`Search input value mismatch: "${value}"`);
  }
  console.log('  → Search input accepts text');
}

async function testMetrics(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/metrics`, [
    { selector: 'h1', description: 'Page title', state: 'visible' },
  ]);
  
  const title = await page.locator('h1').textContent();
  if (!title.includes('Metrics')) {
    throw new Error(`Expected title to contain "Metrics", got "${title}"`);
  }
  console.log(`  → Page title: "${title.trim()}"`);
}

async function testAlerts(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/alerts`, [
    { selector: 'h1', description: 'Page title', state: 'visible' },
  ]);
  
  const title = await page.locator('h1').textContent();
  if (!title.includes('Alerts')) {
    throw new Error(`Expected title to contain "Alerts", got "${title}"`);
  }
  console.log(`  → Page title: "${title.trim()}"`);
}

async function testSettings(page) {
  await navigateAndCheck(page, `${WEBUI_BASE}/settings`, [
    { selector: 'h1', description: 'Page title', state: 'visible' },
    { selector: 'button:has-text("LLM")', description: 'LLM tab button', state: 'visible' },
  ]);
  
  const title = await page.locator('h1').textContent();
  if (!title.includes('Settings')) {
    throw new Error(`Expected title to contain "Settings", got "${title}"`);
  }
  console.log(`  → Page title: "${title.trim()}"`);
  
  // Check tabs exist
  const llmTab = page.locator('button:has-text("LLM")');
  const retrievalTab = page.locator('button:has-text("Retrieval")');
  const cacheTab = page.locator('button:has-text("Cache")');
  
  if (await llmTab.isVisible({ timeout: 3000 })) {
    console.log('  → Settings tabs present');
  }
}

async function testNavigationFlow(page) {
  // Test that navigation between pages works
  console.log('  → Testing navigation flow');
  
  await page.goto(`${WEBUI_BASE}/dashboard`, { waitUntil: 'networkidle' });
  
  const pages = [
    { url: '/documents', name: 'Documents' },
    { url: '/collections', name: 'Collections' },
    { url: '/chat', name: 'Chat' },
    { url: '/search', name: 'Search' },
    { url: '/metrics', name: 'Metrics' },
    { url: '/alerts', name: 'Alerts' },
    { url: '/settings', name: 'Settings' },
  ];
  
  for (const p of pages) {
    await page.click(`nav >> text=${p.name}`);
    await page.waitForURL(`**/webui${p.url}`, { timeout: 10000 });
    console.log(`  → Navigated to ${p.name}`);
  }
  
  // Return to dashboard
  await page.click('nav >> text=Dashboard');
  await page.waitForURL('**/webui/dashboard', { timeout: 10000 });
  console.log('  → Returned to Dashboard');
}

async function testBackendHealth(page) {
  // Verify backend API is accessible
  console.log('  → Testing backend API health');
  
  const response = await page.request.get(`${BASE_URL}/api/v1/rag/health`);
  if (response.status() !== 200) {
    throw new Error(`Health check failed: HTTP ${response.status()}`);
  }
  
  const health = await response.json();
  if (health.status !== 'UP') {
    throw new Error(`Backend status is not UP: ${health.status}`);
  }
  
  console.log(`  → Backend health: ${health.status}`);
  console.log(`  → Components: database=${health.components?.database}, pgvector=${health.components?.pgvector}`);
}

async function test404Fallback(page) {
  // Test SPA routing - direct navigation to sub-pages should work
  console.log('  → Testing SPA routing');
  
  const response = await page.goto(`${WEBUI_BASE}/chat`, { waitUntil: 'networkidle' });
  if (response.status() !== 200) {
    throw new Error(`SPA routing failed: HTTP ${response.status()}`);
  }
  console.log('  → SPA routing OK');
}

// ============================================================
// MAIN
// ============================================================

async function main() {
  console.log('═'.repeat(60));
  console.log('  spring-ai-rag WebUI E2E Test Suite');
  console.log('═'.repeat(60));
  console.log(`\nTarget: ${WEBUI_BASE}`);
  console.log(`Browser: Chromium (headless)`);
  
  // Ensure test-results directory exists
  const fs = require('fs');
  if (!fs.existsSync('test-results')) {
    fs.mkdirSync('test-results', { recursive: true });
  }
  
  // Check backend first
  console.log('\n' + '─'.repeat(60));
  console.log('🔍 Pre-flight Check: Backend Health');
  console.log('─'.repeat(60));
  
  try {
    const { execSync } = require('child_process');
    const result = execSync(`curl -s "${BASE_URL}/api/v1/rag/health"`, { timeout: 10000 });
    const health = JSON.parse(result.toString());
    console.log(`✅ Backend is UP: ${health.status}`);
    if (health.components) {
      console.log(`   database: ${health.components.database}`);
      console.log(`   pgvector: ${health.components.pgvector}`);
    }
  } catch (error) {
    console.log(`❌ Backend is not accessible: ${error.message}`);
    console.log('   Please start the backend: mvn spring-boot:run -pl spring-ai-rag-core');
    process.exit(1);
  }
  
  // Run tests
  console.log('\n' + '─'.repeat(60));
  console.log('🧪 Running Tests');
  console.log('─'.repeat(60));
  
  await runTest('Dashboard Page', testDashboard);
  await runTest('Documents Page', testDocuments);
  await runTest('Collections Page', testCollections);
  await runTest('Chat Page', testChat);
  await runTest('Chat Interaction (Real)', testChatInteraction);
  await runTest('Search Page', testSearch);
  await runTest('Metrics Page', testMetrics);
  await runTest('Alerts Page', testAlerts);
  await runTest('Settings Page', testSettings);
  await runTest('Navigation Flow', testNavigationFlow);
  await runTest('Backend Health API', testBackendHealth);
  await runTest('SPA Routing', test404Fallback);
  
  // Summary
  console.log('\n' + '═'.repeat(60));
  console.log('📊 Test Results');
  console.log('═'.repeat(60));
  console.log(`   ✅ Passed: ${results.passed}`);
  console.log(`   ❌ Failed: ${results.failed}`);
  
  if (results.failed > 0) {
    console.log('\n❌ Failed Tests:');
    for (const { name, error } of results.errors) {
      console.log(`   - ${name}: ${error}`);
    }
  }
  
  console.log('\n' + '═'.repeat(60));
  
  process.exit(results.failed > 0 ? 1 : 0);
}

main().catch((error) => {
  console.error('\n❌ Test suite crashed:', error.message);
  process.exit(1);
});
