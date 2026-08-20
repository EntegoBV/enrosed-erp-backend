#!/usr/bin/env node

import { readFile } from 'node:fs/promises';
import path from 'node:path';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 1) {
  const key = process.argv[index];
  if (key === '--apply-full-reset') {
    args.set(key, true);
    continue;
  }
  const value = process.argv[index + 1];
  if (!key.startsWith('--') || value == null || value.startsWith('--')) {
    throw new Error(`Ongeldig argument: ${key}`);
  }
  args.set(key, value);
  index += 1;
}

const manifestPath = path.resolve(requiredArg('--manifest'));
const baseUrl = new URL(requiredArg('--base-url'));
if (baseUrl.protocol !== 'https:' && !['localhost', '127.0.0.1', '::1'].includes(baseUrl.hostname)) {
  throw new Error('Gebruik HTTPS, behalve voor een lokale migratieserver.');
}

const username = process.env.ENROSED_MIGRATION_ADMIN_USERNAME;
const password = process.env.ENROSED_MIGRATION_ADMIN_PASSWORD;
if (!username || !password) {
  throw new Error('Stel ENROSED_MIGRATION_ADMIN_USERNAME en ENROSED_MIGRATION_ADMIN_PASSWORD in.');
}

const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const preflight = await post('preflight', manifest);
process.stdout.write(`${JSON.stringify({ phase: 'preflight', result: preflight }, null, 2)}\n`);

if (!args.has('--apply-full-reset')) {
  process.stdout.write('Alleen preflight uitgevoerd. Voeg --apply-full-reset toe voor de expliciete clean-slate import.\n');
  process.exit(0);
}

if (preflight.valid !== true) {
  throw new Error('Preflight is niet geldig; de database is niet gewijzigd.');
}
if (preflight.fullResetConfirmationRequired !== 'RESET-ALL-ENROSED-DATA') {
  throw new Error('De server bevestigt niet de verwachte volledige-resetgrens.');
}

const result = await post('apply', {
  manifest,
  replaceExistingProducts: true,
  deleteReferencingTestGraphs: true,
  fullReset: true,
  confirmation: preflight.fullResetConfirmationRequired,
});
process.stdout.write(`${JSON.stringify({ phase: 'apply', result }, null, 2)}\n`);

async function post(endpoint, body) {
  const response = await fetch(new URL(`/api/admin/catalog-migration/${endpoint}`, baseUrl), {
    method: 'POST',
    headers: {
      authorization: `Basic ${Buffer.from(`${username}:${password}`, 'utf8').toString('base64')}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  const raw = await response.text();
  let decoded;
  try {
    decoded = raw ? JSON.parse(raw) : null;
  } catch {
    decoded = { message: raw.slice(0, 500) };
  }
  if (!response.ok) {
    throw new Error(`Migratie-endpoint ${endpoint} gaf HTTP ${response.status}: ${decoded?.message ?? 'onbekende fout'}`);
  }
  return decoded;
}

function requiredArg(name) {
  const value = args.get(name);
  if (!value) throw new Error(`Verplicht argument ontbreekt: ${name}`);
  return value;
}
