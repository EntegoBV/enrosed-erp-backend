import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index]?.replace(/^--/, ''), process.argv[index + 1]);
}
const productsPath = path.resolve(args.get('products') ?? process.env.ENROSED_SHOPIFY_PRODUCTS ?? 'shopify-products.json');
const detailDirectory = path.resolve(args.get('output') ?? process.env.ENROSED_SHOPIFY_DETAILS ?? 'shopify-product-details');
const snapshot = JSON.parse(await readFile(productsPath, 'utf8'));

await mkdir(detailDirectory, { recursive: true });

for (const product of snapshot.products) {
  const response = await fetch(`https://enrosed.com/products/${encodeURIComponent(product.handle)}.js`, {
    headers: { accept: 'application/json' },
  });
  if (!response.ok) {
    throw new Error(`Shopify detail ${product.handle}: HTTP ${response.status}`);
  }

  const detail = await response.json();
  if (String(detail.id) !== String(product.id) || detail.handle !== product.handle) {
    throw new Error(`Shopify detail identity mismatch for ${product.handle}`);
  }

  const output = path.join(detailDirectory, `${product.handle}.json`);
  await writeFile(output, `${JSON.stringify(detail, null, 2)}\n`, 'utf8');
  process.stdout.write(`${path.basename(output)}\n`);
}
