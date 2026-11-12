/**
 * Cleanup script for residual admission policies
 * Run: npx ts-node tests/e2e/scripts/cleanup-admission-policies.ts
 *
 * This script removes all enabled admission policies from the database
 * to prevent D2 policies from polluting D1 E2E tests.
 */

import { config } from 'dotenv';
config();

const API_BASE = process.env.API_BASE || 'http://localhost:8080';

async function login(): Promise<string> {
  const response = await fetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: process.env.ADMIN_USERNAME || 'admin',
      password: process.env.ADMIN_PASSWORD || 'admin123'
    })
  });

  if (!response.ok) {
    throw new Error(`Login failed: ${response.status}`);
  }

  const data = await response.json();
  return data.data?.token || data.token;
}

async function cleanupPolicies(token: string): Promise<void> {
  // Get all policies
  const response = await fetch(`${API_BASE}/api/release-environment-policies`, {
    headers: { Authorization: `Bearer ${token}` }
  });

  if (!response.ok) {
    throw new Error(`Failed to get policies: ${response.status}`);
  }

  const data = await response.json();
  const policies = data.data || [];

  console.log(`Found ${policies.length} policies`);

  let deleted = 0;
  for (const policy of policies) {
    if (policy.enabled === true) {
      console.log(`Deleting policy ${policy.id}: envId=${policy.envId}, action=${policy.action}, riskLevel=${policy.riskLevel}`);

      const deleteResponse = await fetch(`${API_BASE}/api/release-environment-policies/${policy.id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` }
      });

      if (deleteResponse.ok) {
        deleted++;
        console.log(`  ✓ Deleted policy ${policy.id}`);
      } else {
        console.log(`  ✗ Failed to delete policy ${policy.id}: ${deleteResponse.status}`);
      }
    }
  }

  console.log(`\nCleanup complete: ${deleted} policies deleted`);
}

async function main(): Promise<void> {
  console.log('Cleaning up residual admission policies...\n');

  try {
    const token = await login();
    console.log('Login successful\n');

    await cleanupPolicies(token);
  } catch (error) {
    console.error('Error:', error);
    process.exit(1);
  }
}

main();
