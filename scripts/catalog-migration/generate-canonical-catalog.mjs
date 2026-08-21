import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key?.startsWith('--') || value == null) throw new Error(`Invalid argument near ${key ?? '<end>'}`);
  args.set(key.slice(2), value);
}
const requiredPath = (name, environmentName) => {
  const value = args.get(name) ?? process.env[environmentName];
  if (!value) throw new Error(`Missing --${name} or ${environmentName}`);
  return path.resolve(value);
};
const websiteRepo = requiredPath('website-repo', 'ENROSED_WEBSITE_REPO');
const odooWorkbookPath = requiredPath('odoo-workbook', 'ENROSED_ODOO_WORKBOOK');
const pdfPath = requiredPath('pdf', 'ENROSED_CONTAINER_PDF');
const sourceAuditPath = requiredPath('source-inventory', 'ENROSED_SOURCE_INVENTORY');
const shopifyProductsPath = requiredPath('shopify-products', 'ENROSED_SHOPIFY_PRODUCTS');
const shopifyCartPath = requiredPath('shopify-cart', 'ENROSED_SHOPIFY_CART');
const detailDirectory = requiredPath('shopify-details', 'ENROSED_SHOPIFY_DETAILS');
const mappingPath = requiredPath('mapping', 'ENROSED_CATALOG_MAPPING');
const outputDirectory = requiredPath('output', 'ENROSED_CATALOG_OUTPUT');

const requireFromWebsite = createRequire(path.join(websiteRepo, 'package.json'));
const { build } = requireFromWebsite('esbuild');
const sharp = requireFromWebsite('sharp');

const readJson = async (file) => JSON.parse(await readFile(file, 'utf8'));
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const hashFile = async (file) => sha256(await readFile(file));
const normalizeColour = (value) => {
  if (value == null || value === 'Default Title') return null;
  const labels = { rood: 'Red', roos: 'Pink', blauw: 'Blue', wit: 'White' };
  return labels[String(value).toLowerCase()] ?? String(value);
};
const slug = (value) => String(value)
  .normalize('NFKD')
  .replace(/[\u0300-\u036f]/g, '')
  .toLowerCase()
  .replace(/[^a-z0-9]+/g, '-')
  .replace(/^-|-$/g, '');
const generatedSku = (familyKey, variantKey) => `ENR-${slug(familyKey)}-${slug(variantKey)}`.toUpperCase();
const parseDecimal = (value) => Number(String(value).replace(',', '.'));
const nonBlank = (value) => typeof value === 'string' && value.trim().length > 0;

const sourceInventory = await readJson(sourceAuditPath);
const shopifySnapshot = await readJson(shopifyProductsPath);
const shopifyCart = await readJson(shopifyCartPath);
const mapping = await readJson(mappingPath);
const generatedProducts = await readJson(path.join(websiteRepo, 'src/data/products.generated.json'));

if (shopifyCart.currency !== 'EUR') {
  throw new Error(`Expected verified Shopify currency EUR, received ${shopifyCart.currency}`);
}

await mkdir(outputDirectory, { recursive: true });
const bundledCatalog = path.join(outputDirectory, '.catalog-bundle.mjs');
await build({
  entryPoints: [path.join(websiteRepo, 'src/data/catalog.ts')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  outfile: bundledCatalog,
  logLevel: 'silent',
});
const catalogModule = await import(`${pathToFileURL(bundledCatalog).href}?v=${Date.now()}`);
await unlink(bundledCatalog);

const rawByHandle = new Map(generatedProducts.products.map((product) => [product.handle, product]));
const shopifyByHandle = new Map(shopifySnapshot.products.map((product) => [product.handle, product]));
const catalogByHandle = new Map(catalogModule.catalogProducts.map((product) => [product.handle, product]));
const categoryByHandle = new Map(catalogModule.catalogCategories.map((category, position) => [
  category.handle,
  { key: category.handle, name: category.title, eyebrow: category.eyebrow, description: category.description, position },
]));
// Audited presentation choices from the retired website. These values are ETL input,
// not a runtime colour-name resolver; the generated manifest remains the source of truth.
const websiteSwatchHex = new Map(Object.entries({
  Red: '#A91F32', Pink: '#D889A2', Blue: '#6C8FC4', White: '#EEE8DD',
  Navy: '#243253', 'Cherry Pink': '#D9577E', 'Light Blue': '#9CC5DE',
}));
const familyCardFeaturedVariant = new Map(Object.entries({
  'rose-diamonds-within-display': 'shopify-46685588127913',
  'preserved-single-rose-in-display': 'shopify-46736420765865',
  'preserved-bowl-rose': 'shopify-46736421683369',
  'bowl-rose-xl': 'shopify-46736106717353',
  'cobalt-blue-roos-in-glazen-stolp': 'shopify-44784500277417',
  'one-rose-in-box': 'shopify-44784495526057',
  'roses-in-box-16pcs': 'shopify-44784490873001',
  'roses-in-box-9pcs': 'shopify-44784491397289',
  'rose-in-dome-xl': 'shopify-44887957340329',
  'soap-rose-box-led': 'shopify-46685592944809',
}));
const collectionPresentation = new Map(Object.entries({
  'display-roses': {
    mobileName: 'Signature displays', featuredCanonicalVariantKey: 'shopify-46685588095145',
  },
  divers: {
    mobileName: 'Domes & boxes', featuredCanonicalVariantKey: 'shopify-44784500277417',
  },
  'rose-bears': {
    mobileName: 'Soap & foam', featuredCanonicalVariantKey: 'shopify-44784482320553',
  },
}));
const odooByName = new Map(sourceInventory.sources.odooWorkbook.rows.map((row) => [row.name, row]));
const pdfByNumber = new Map(sourceInventory.sources.pdf.rows.map((row) => [row.productNo, row]));
const crossSourceByHandle = new Map(sourceInventory.crossSource.matches.map((match) => [match.websiteHandle, match]));

const loadDetail = (handle) => readJson(path.join(detailDirectory, `${handle}.json`));
const sourceRef = (sourceType, sourceLocation, sourceValue, fieldPath = null, confidence = 'HIGH') => ({
  sourceType,
  sourceLocation,
  fieldPath,
  sourceValue,
  confidence,
});
const conflict = (code, message, {
  severity = 'WARNING', confidence = null, relatedSourceRecords = [],
} = {}) => ({ code, message, severity, confidence, relatedSourceRecords, status: 'OPEN' });

const priceObservationsForOdoo = (row) => {
  const observations = [];
  if (row.costPriceRaw !== 0) {
    observations.push({
      priceType: 'ODOO_STRUCTURED_COST',
      amount: row.costPriceRaw,
      currency: null,
      taxContext: row.purchaseTaxLabel,
      incoterm: null,
      market: null,
      rawText: String(row.costPriceRaw),
      sourceLocation: row.source,
    });
  }

  for (const note of row.priceContextNotesFromDescription) {
    const candidates = [];
    const add = (priceType, match, explicitCurrency = null, market = null, incoterm = null) => {
      if (!match) return;
      candidates.push({
        priceType,
        amount: parseDecimal(match[1]),
        currency: explicitCurrency,
        taxContext: null,
        incoterm,
        market,
        rawText: note,
        sourceLocation: `${row.source} Omschrijving`,
      });
    };
    add('EXW', note.match(/(?:exw\s*)(\d+(?:[.,]\d+)?)/i), null, null, 'EXW');
    add('EXW', note.match(/(\d+(?:[.,]\d+)?)\s*exw/i), null, null, 'EXW');
    const franceAfter = note.match(/frans(?:\s+verkoop)?(?:\s+ddp)?\s*(\d+(?:[.,]\d+)?)/i);
    const franceBefore = note.match(/^\s*(\d+(?:[.,]\d+)?)\s*frans(?:\s+verkoop)?(?:\s+ddp)?/i);
    const franceMatch = franceAfter ?? franceBefore;
    const franceCurrency = franceMatch && /\b(?:eur|euro)\b/i.test(note.slice(franceMatch.index ?? 0))
      ? 'EUR'
      : null;
    add('FRANCE_SALES', franceMatch, franceCurrency, 'FR', /ddp/i.test(note) ? 'DDP' : null);
    add('PURCHASE', note.match(/(\d+(?:[.,]\d+)?)\s*inkoop/i), null);
    add('CIF', note.match(/cif\s*\$\s*(\d+(?:[.,]\d+)?)/i), 'USD', null, 'CIF');

    const unique = new Map(candidates.map((item) => [`${item.priceType}:${item.amount}:${item.currency}`, item]));
    if (unique.size === 0) {
      observations.push({
        priceType: 'NARRATIVE_UNPARSED',
        amount: null,
        currency: null,
        taxContext: null,
        incoterm: null,
        market: null,
        rawText: note,
        sourceLocation: `${row.source} Omschrijving`,
      });
    } else {
      observations.push(...unique.values());
    }
  }
  for (const line of row.descriptionText?.split(/\r?\n/) ?? []) {
    if (!/^\s*\d+(?:[.,]\d+)?\s*$/.test(line)) continue;
    observations.push({
      priceType: 'NARRATIVE_UNCLASSIFIED',
      amount: parseDecimal(line.trim()),
      currency: null,
      taxContext: null,
      incoterm: null,
      market: null,
      rawText: line.trim(),
      sourceLocation: `${row.source} Omschrijving`,
    });
  }
  return observations;
};

const descriptionWeightObservations = (row) => {
  if (!row.descriptionText) return [];
  return [...row.descriptionText.matchAll(/weight\s*:\s*(\d+(?:[.,]\d+)?)\s*(kgs?|kg)\b/gi)]
    .map((match) => ({
      amount: parseDecimal(match[1]),
      unit: 'kg',
      rawText: match[0],
      sourceLocation: `${row.source} Omschrijving`,
    }));
};

const odooProvenance = (row) => [
  sourceRef('ODOO_XLSX', row.source, row.name, 'name'),
  sourceRef('ODOO_XLSX', `${row.source} Omschrijving`, row.descriptionText, 'description'),
  sourceRef('ODOO_XLSX', `${row.source} Gewicht`, row.weightRaw, 'weight', 'MEDIUM'),
  sourceRef('ODOO_XLSX', `${row.source} Kostprijs`, row.costPriceRaw, 'structuredCostRaw'),
  sourceRef('ODOO_XLSX', `${row.source} Aanwezige voorraad`, row.stockOnHand, 'stockQuantity'),
  sourceRef('ODOO_XLSX', `${row.source} Voorspelde aantal`, row.forecastQuantity, 'forecastQuantity'),
  sourceRef('ODOO_XLSX', `${row.source} Eenheid`, row.unit, 'stockUnit'),
  sourceRef('ODOO_XLSX', `${row.source} Btw Inkoop`, row.purchaseTaxLabel, 'purchaseTaxLabel'),
  ...descriptionWeightObservations(row).map((item) => sourceRef(
    'ODOO_XLSX',
    item.sourceLocation,
    { amount: item.amount, unit: item.unit, rawText: item.rawText },
    'weightObservation',
  )),
];

const parseTriple = (raw) => {
  if (!nonBlank(raw)) return null;
  const values = [...raw.matchAll(/\d+(?:[.,]\d+)?/g)].map((match) => parseDecimal(match[0]));
  return values.length >= 3 ? values.slice(0, 3) : null;
};

const canonicalOdooPackage = (
  row,
  variantCanonicalKey,
  { operational = false, confidence = 'MEDIUM' } = {},
) => {
  if (!row.descriptionText) return null;
  const lines = row.descriptionText.split(/\r?\n/);
  const cartonLine = lines.find((line) => /(?:outer\s*carton|outercarton|carton)/i.test(line) && parseTriple(line))
    ?? lines.find((line) => parseTriple(line) && /pcs(?:\/|\s*per\s*)carton/i.test(row.descriptionText));
  const triple = cartonLine ? parseTriple(cartonLine) : null;
  const piecesMatch = row.descriptionText.match(/(\d+)\s*(?:pcs(?:\/|\s*per\s*)carton|pcs\/carton)/i);
  if (!triple || !piecesMatch) return null;
  return {
    packageType: 'OUTER_CARTON',
    variantCanonicalKey,
    dimensions: { values: triple, unit: 'cm', rawValue: cartonLine.trim(), axisMeaningConfirmed: false },
    piecesPerPackage: Number(piecesMatch[1]),
    sourceType: 'ODOO_XLSX',
    sourceLocation: `${row.source} Omschrijving`,
    operational,
    confidence,
  };
};

const odooDescriptionDimensions = (row) => {
  if (!row.descriptionText) return [];
  const dimensions = [];
  for (const line of row.descriptionText.split(/\r?\n/)) {
    const match = line.match(
      /^\s*(Acrylic|Giftbox)\s*:?[ \t]*(\d+(?:[.,]\d+)?(?:\s*[x×*]\s*\d+(?:[.,]\d+)?){1,2})/i,
    );
    if (!match) continue;
    dimensions.push({
      dimensionType: match[1].toLowerCase() === 'acrylic' ? 'PRODUCT_DISPLAY' : 'GIFT_BOX',
      values: [...match[2].matchAll(/\d+(?:[.,]\d+)?/g)].map((item) => parseDecimal(item[0])),
      unit: 'cm',
      rawValue: line.trim(),
      axisMeaningConfirmed: false,
      sourceType: 'ODOO_XLSX',
      sourceLocation: `${row.source} Omschrijving`,
      operational: false,
      confidence: 'MEDIUM',
    });
  }
  return dimensions;
};

const pdfDimension = (pdf, { operational = true, confidence = 'HIGH' } = {}) => {
  const match = pdf?.labelRaw?.match(/\(([^)]+)\)/);
  if (!match) return null;
  // Some labels append a rose count inside the same parentheses, for example
  // `15*30 3 rose`. Only the leading multiplication-delimited measurement is
  // a dimension; a bare trailing number must never become a centimetre axis.
  const measurement = match[1].match(
    /\d+(?:[.,]\d+)?(?:\s*[x×*]\s*\d+(?:[.,]\d+)?){1,2}/i,
  )?.[0];
  if (!measurement) return null;
  const values = [...measurement.matchAll(/\d+(?:[.,]\d+)?/g)]
    .map((item) => parseDecimal(item[0]));
  return {
    dimensionType: 'PRODUCT_DISPLAY',
    values,
    unit: 'cm',
    rawValue: match[1],
    axisMeaningConfirmed: false,
    sourceType: 'PDF',
    sourceLocation: `ContainerOverzichtRozen.pdf p.1 product ${pdf.productNo}`,
    operational,
    confidence,
  };
};

const pdfPackage = (pdf, { operational = true, confidence = 'HIGH' } = {}) => {
  if (!pdf) return null;
  return {
    packageType: 'OUTER_CARTON',
    variantCanonicalKey: null,
    dimensions: {
      values: parseTriple(pdf.cartonDimensionsRaw),
      unit: pdf.cartonDimensionUnit,
      rawValue: pdf.cartonDimensionsRaw,
      axisMeaningConfirmed: false,
    },
    piecesPerPackage: pdf.piecesPerCarton,
    sourceType: 'PDF',
    sourceLocation: `ContainerOverzichtRozen.pdf p.1 product ${pdf.productNo}`,
    operational,
    confidence,
  };
};

const pdfPriceObservations = (pdf) => pdf ? [
  {
    priceType: 'PDF_UNIT_UNCLASSIFIED', amount: pdf.unitPriceRaw, currency: pdf.currency,
    taxContext: null, incoterm: null, market: null, rawText: `${pdf.unitPriceRaw}`,
    sourceLocation: `ContainerOverzichtRozen.pdf p.1 product ${pdf.productNo}`,
  },
  {
    priceType: 'PDF_TOTAL_UNCLASSIFIED', amount: pdf.totalRaw, currency: pdf.currency,
    taxContext: null, incoterm: null, market: null, rawText: `${pdf.totalRaw} for quantity ${pdf.quantity}`,
    sourceLocation: `ContainerOverzichtRozen.pdf p.1 product ${pdf.productNo}`,
  },
] : [];

const odooNameDimension = (row) => {
  const match = row.name.match(/(\d+(?:[.,]\d+)?(?:\s*[x×*]\s*\d+(?:[.,]\d+)?)*)\s*cm/i);
  if (!match) return null;
  const values = [...match[1].matchAll(/\d+(?:[.,]\d+)?/g)].map((item) => parseDecimal(item[0]));
  return {
    dimensionType: 'PRODUCT_DISPLAY', values, unit: 'cm', rawValue: match[0],
    axisMeaningConfirmed: false, sourceType: 'ODOO_XLSX', sourceLocation: `${row.source} Naam`,
    operational: false, confidence: 'HIGH',
  };
};

const makeOdooVariant = (familyKey, row, position, color = null, name = row.name, suffix = color ?? row.name) => {
  const canonicalVariantKey = `odoo-${slug(row.name)}`;
  const identifiers = [
    { source: 'ODOO', identifierType: 'TEMPLATE_NAME', value: row.name, confirmed: true },
    ...row.eanLikeValuesFromDescription.map((value) => ({
      source: 'ODOO', identifierType: 'PACKAGING_GTIN_CANDIDATE', value, confirmed: false,
    })),
  ];
  return {
    canonicalVariantKey,
    sku: generatedSku(familyKey, suffix),
    skuProvenance: 'GENERATED_INTERNAL',
    sourceSku: null,
    name,
    color,
    size: null,
    colourHex: null,
    position,
    active: true,
    inventoryKnown: true,
    stockQuantity: row.stockOnHand,
    publicAvailability: null,
    barcode: null,
    externalIdentifiers: identifiers,
    priceObservations: priceObservationsForOdoo(row),
    provenance: odooProvenance(row),
    packages: [canonicalOdooPackage(row, canonicalVariantKey)].filter(Boolean),
  };
};

const highMatchByHandle = new Map(mapping.highConfidenceMatches.map((match) => [match.websiteHandle, match]));

const buildWebsiteFamily = async (product, productPosition) => {
  const raw = rawByHandle.get(product.handle);
  const shopify = shopifyByHandle.get(product.handle);
  const catalog = catalogByHandle.get(product.handle);
  const detail = await loadDetail(product.handle);
  const imageMedia = detail.media.filter((media) => media.media_type === 'image');
  const category = categoryByHandle.get(product.category);
  if (!raw || !shopify || !catalog || !category) throw new Error(`Incomplete website sources for ${product.handle}`);
  if (String(detail.id) !== String(shopify.id)) throw new Error(`Shopify ID mismatch for ${product.handle}`);
  if (raw.gallery.length !== imageMedia.length) {
    throw new Error(`Gallery/image-media count mismatch for ${product.handle}: ${raw.gallery.length} vs ${imageMedia.length}`);
  }

  const variants = raw.variants.map((variant, position) => {
    const canonicalVariantKey = `shopify-${variant.id}`;
    const color = normalizeColour(variant.color);
    const sourceVariant = shopify.variants.find((item) => String(item.id) === String(variant.id));
    if (!sourceVariant) throw new Error(`Missing Shopify source variant ${variant.id} for ${product.handle}`);
    return {
      canonicalVariantKey,
      sku: generatedSku(product.handle, color ?? 'default'),
      skuProvenance: 'GENERATED_INTERNAL',
      sourceSku: null,
      name: color ?? raw.title,
      color,
      size: null,
      colourHex: color == null ? null : (websiteSwatchHex.get(color) ?? null),
      position,
      active: true,
      inventoryKnown: false,
      stockQuantity: null,
      publicAvailability: variant.available,
      barcode: null,
      externalIdentifiers: [
        { source: 'SHOPIFY', identifierType: 'VARIANT_ID', value: String(variant.id), confirmed: true },
      ],
      priceObservations: [
        {
          priceType: 'SHOPIFY_RETAIL', amount: variant.price, currency: 'EUR', taxContext: null,
          incoterm: null, market: 'ONLINE_RETAIL', rawText: String(variant.price),
          sourceLocation: `enrosed.com/products/${product.handle}.js variant ${variant.id}`,
        },
        ...(variant.compareAtPrice == null ? [] : [{
          priceType: 'SHOPIFY_COMPARE_AT', amount: variant.compareAtPrice, currency: 'EUR', taxContext: null,
          incoterm: null, market: 'ONLINE_RETAIL', rawText: String(variant.compareAtPrice),
          sourceLocation: `enrosed.com/products/${product.handle}.js variant ${variant.id}`,
        }]),
      ],
      provenance: [
        sourceRef('WEBSITE_FRONTEND', `products.generated.json ${product.handle} variant ${variant.id}`, variant.title, 'name'),
        sourceRef('SHOPIFY', `products/${product.handle}.js variant ${variant.id}`, variant.available, 'publicAvailability'),
        sourceRef('SHOPIFY', `products.json ${product.handle} variant ${variant.id}`, sourceVariant.grams, 'weightGrams'),
        sourceRef('SHOPIFY', `products.json ${product.handle} variant ${variant.id}`, sourceVariant.taxable, 'taxable'),
        sourceRef(
          'SHOPIFY',
          `products.json ${product.handle} variant ${variant.id}`,
          sourceVariant.requires_shipping,
          'requiresShipping',
        ),
      ],
      packages: [],
    };
  });

  const match = highMatchByHandle.get(product.handle);
  const pdf = match?.pdfProductNo == null ? null : pdfByNumber.get(match.pdfProductNo);
  const familyOdooProvenance = [];
  const familyOdooPriceObservations = [];
  const matchedOdooPackages = [];
  const weightConflicts = [];
  const matchedPurchaseTaxRecords = [];
  const matchedOdooWeightRecords = [];
  const matchedOdooDimensions = [];
  const matchedDescriptionWeightRecords = [];
  if (match) {
    for (const familyRowName of match.familyOnlyOdooRows ?? []) {
      const row = odooByName.get(familyRowName);
      if (!row) throw new Error(`Missing Odoo family row ${familyRowName}`);
      familyOdooProvenance.push(...odooProvenance(row));
      familyOdooPriceObservations.push(...priceObservationsForOdoo(row));
    }
    for (const rowMapping of match.odooRows) {
      const row = odooByName.get(rowMapping.name);
      if (!row) throw new Error(`Missing Odoo row ${rowMapping.name}`);
      const targetColour = rowMapping.targetVariantColor ?? rowMapping.variantColor;
      const target = variants.find((variant) => variant.color === targetColour)
        ?? (rowMapping.variantColor == null && variants.length === 1 ? variants[0] : null);
      if (!target) throw new Error(`No ${targetColour} website variant for ${product.handle}`);
      target.inventoryKnown = true;
      target.stockQuantity = row.stockOnHand;
      target.externalIdentifiers.push(
        { source: 'ODOO', identifierType: 'TEMPLATE_NAME', value: row.name, confirmed: true },
        ...row.eanLikeValuesFromDescription.map((value) => ({
          source: 'ODOO', identifierType: 'PACKAGING_GTIN_CANDIDATE', value, confirmed: false,
        })),
      );
      target.priceObservations.push(...priceObservationsForOdoo(row));
      target.provenance.push(...odooProvenance(row));
      if (nonBlank(row.purchaseTaxLabel)) {
        matchedPurchaseTaxRecords.push({ row: row.name, value: row.purchaseTaxLabel });
      }
      if (row.weightRaw > 0) {
        matchedOdooWeightRecords.push({ row: row.name, value: row.weightRaw });
      }
      matchedOdooDimensions.push(...odooDescriptionDimensions(row));
      matchedDescriptionWeightRecords.push(...descriptionWeightObservations(row)
        .map((item) => ({ row: row.name, structured: row.weightRaw, ...item })));
      const sourcePackage = canonicalOdooPackage(row, target.canonicalVariantKey, {
        operational: !pdf,
        confidence: 'MEDIUM',
      });
      if (sourcePackage) {
        target.packages.push(sourcePackage);
        matchedOdooPackages.push(sourcePackage);
      }
      const shopifyWeightGrams = target.provenance.find((item) => (
        item.sourceType === 'SHOPIFY' && item.fieldPath === 'weightGrams'
      ))?.sourceValue;
      if (row.weightRaw > 0 && Number(shopifyWeightGrams) > 0) {
        weightConflicts.push(conflict(
          'WEIGHT_SOURCE_CONFLICT',
          `Odoo weight ${row.weightRaw} has no declared unit while Shopify stores ${shopifyWeightGrams} grams; neither is promoted to an operational weight.`,
          { relatedSourceRecords: [row.source, `Shopify variant ${target.canonicalVariantKey.replace('shopify-', '')}`] },
        ));
      }
    }
  }

  const reverseVariantImage = new Map();
  for (const rawVariant of raw.variants) {
    if (typeof rawVariant.imageIndex === 'number' && !reverseVariantImage.has(rawVariant.imageIndex)) {
      reverseVariantImage.set(rawVariant.imageIndex, `shopify-${rawVariant.id}`);
    }
  }

  const images = [];
  for (let position = 0; position < raw.gallery.length; position += 1) {
    const gallery = raw.gallery[position];
    const media = imageMedia[position];
    if (gallery.width !== media.width || gallery.height !== media.height) {
      throw new Error(
        `Image order/dimension mismatch for ${product.handle} position ${position + 1}: `
        + `${gallery.width}x${gallery.height} vs ${media.width}x${media.height}`,
      );
    }
    const smallPath = path.join(websiteRepo, 'public', gallery.small.replace(/^\//, ''));
    const largePath = path.join(websiteRepo, 'public', gallery.large.replace(/^\//, ''));
    const [smallBytes, largeBytes, smallMeta, largeMeta] = await Promise.all([
      readFile(smallPath), readFile(largePath), sharp(smallPath).metadata(), sharp(largePath).metadata(),
    ]);
    images.push({
      sourceId: String(media.id),
      sourceUrl: media.src,
      sourceWidth: media.width,
      sourceHeight: media.height,
      filename: `${String(position + 1).padStart(2, '0')}.webp`,
      contentType: 'image/webp',
      position,
      altText: nonBlank(media.alt) ? media.alt.trim() : catalog.gallery[position].alt,
      altTextSource: nonBlank(media.alt) ? 'SHOPIFY' : 'WEBSITE_GENERATED',
      variantCanonicalKey: reverseVariantImage.get(position) ?? null,
      variantColor: reverseVariantImage.has(position)
        ? variants.find((item) => item.canonicalVariantKey === reverseVariantImage.get(position))?.color ?? null
        : null,
      small: {
        sha256: sha256(smallBytes), width: smallMeta.width, height: smallMeta.height,
        localSourcePath: path.relative(websiteRepo, smallPath), bytesBase64: smallBytes.toString('base64'),
      },
      large: {
        sha256: sha256(largeBytes), width: largeMeta.width, height: largeMeta.height,
        localSourcePath: path.relative(websiteRepo, largePath), bytesBase64: largeBytes.toString('base64'),
      },
    });
  }

  const pdfConflicts = pdf ? [
    conflict(
      'PDF_PRICE_ARITHMETIC_MISMATCH',
      `Shown unit ${pdf.unitPriceRaw} × ${pdf.quantity} differs from shown total ${pdf.totalRaw}; price context is unknown.`,
      { relatedSourceRecords: [`PDF product ${pdf.productNo}`] },
    ),
  ] : [];
  const sourceMatch = crossSourceByHandle.get(product.handle);
  const sourceConflicts = (sourceMatch?.conflicts ?? []).map((message, index) => conflict(
    `SOURCE_CONFLICT_${index + 1}`, message, { relatedSourceRecords: [product.handle] },
  ));
  const missingVariantImages = raw.variants.filter((variant) => variant.color !== null && variant.imageIndex == null);
  const imageConflicts = missingVariantImages.length === 0 ? [] : [conflict(
    'VARIANT_IMAGE_UNASSIGNED',
    `No colour-specific image is assigned to: ${missingVariantImages.map((item) => normalizeColour(item.color)).join(', ')}.`,
    { severity: 'INFO', relatedSourceRecords: missingVariantImages.map((item) => `Shopify variant ${item.id}`) },
  )];

  const publicFormat = catalog.facts.find((fact) => fact.label === 'Format')?.value ?? null;
  const websiteDimensions = raw.dimensions ? {
    dimensionType: 'PRODUCT_DISPLAY', values: [...raw.dimensions.matchAll(/\d+(?:[.,]\d+)?/g)].map((item) => parseDecimal(item[0])),
    unit: 'cm', rawValue: raw.dimensions, axisMeaningConfirmed: false,
    sourceType: 'WEBSITE_FRONTEND', sourceLocation: `products.generated.json ${product.handle}`,
    operational: !pdf, confidence: 'MEDIUM',
  } : null;
  const odooPackageShapes = new Set(matchedOdooPackages.map((item) => JSON.stringify({
    values: item.dimensions.values,
    unit: item.dimensions.unit,
    piecesPerPackage: item.piecesPerPackage,
  })));
  const commonOdooPackage = !pdf
    && matchedOdooPackages.length === variants.length
    && odooPackageShapes.size === 1
    ? { ...matchedOdooPackages[0], variantCanonicalKey: null }
    : null;
  const taxLabels = new Set(matchedPurchaseTaxRecords.map((item) => item.value));
  const taxConflicts = taxLabels.size > 1 ? [conflict(
    'PURCHASE_TAX_VARIANT_CONFLICT',
    `Odoo purchase-tax labels differ across colours: ${matchedPurchaseTaxRecords.map((item) => `${item.row} = ${item.value}`).join('; ')}.`,
    { relatedSourceRecords: matchedPurchaseTaxRecords.map((item) => item.row) },
  )] : [];
  const odooWeights = new Set(matchedOdooWeightRecords.map((item) => item.value));
  const odooWeightConflicts = odooWeights.size > 1 ? [conflict(
    'ODOO_WEIGHT_VARIANT_CONFLICT',
    `Odoo weights differ across colours and have no declared unit: ${matchedOdooWeightRecords.map((item) => `${item.row} = ${item.value}`).join('; ')}.`,
    { relatedSourceRecords: matchedOdooWeightRecords.map((item) => item.row) },
  )] : [];
  const descriptionWeightConflicts = matchedDescriptionWeightRecords.some((item) => (
    item.structured > 0 && item.amount !== item.structured
  )) ? [conflict(
    'ODOO_STRUCTURED_VS_DESCRIPTION_WEIGHT_CONFLICT',
    `Odoo structured weights have no declared unit and differ from description KG observations: ${matchedDescriptionWeightRecords.map((item) => `${item.row}: structured ${item.structured}, text ${item.amount} kg (${item.rawText})`).join('; ')}.`,
    { relatedSourceRecords: [...new Set(matchedDescriptionWeightRecords.map((item) => item.row))] },
  )] : [];
  const dimensionGroups = Map.groupBy(matchedOdooDimensions, (item) => JSON.stringify({
    dimensionType: item.dimensionType,
    values: item.values,
    unit: item.unit,
  }));
  const commonOdooDimensions = [...dimensionGroups.values()]
    .filter((items) => items.length === (match?.odooRows.length ?? 0))
    .map((items) => ({ ...items[0] }));
  for (const item of commonOdooDimensions) {
    item.operational = !pdf && !websiteDimensions && item.dimensionType === 'PRODUCT_DISPLAY';
  }
  const family = {
    canonicalFamilyKey: product.handle,
    publicHandle: product.handle,
    active: true,
    cardFeaturedCanonicalVariantKey: familyCardFeaturedVariant.get(product.handle) ?? null,
    category,
    collections: [{
      ...category,
      primary: true,
      mobileName: collectionPresentation.get(category.key)?.mobileName ?? null,
      featuredCanonicalVariantKey:
        collectionPresentation.get(category.key)?.featuredCanonicalVariantKey ?? null,
    }],
    productPosition,
    tags: shopify.tags ?? [],
    requestedPublication: { websiteStatus: 'PUBLISHED', orderAppStatus: 'DRAFT', catalogueStatus: 'DRAFT' },
    texts: [
      {
        language: 'EN', name: raw.title, summary: catalog.summary, description: null,
        format: publicFormat, highlights: [...catalog.highlights], seoTitle: catalog.seo.title,
        seoDescription: catalog.seo.description,
      },
      {
        language: 'NL', name: detail.title, summary: null, description: raw.sourceDescription,
        format: null, highlights: raw.sourceHighlights ?? [], seoTitle: null, seoDescription: null,
      },
    ],
    dimensions: [pdfDimension(pdf), websiteDimensions, ...commonOdooDimensions].filter(Boolean),
    packages: [
      pdfPackage(pdf),
      commonOdooPackage,
    ].filter(Boolean),
    images,
    variants,
    externalIdentifiers: [
      { source: 'SHOPIFY', identifierType: 'PRODUCT_ID', value: String(shopify.id), confirmed: true },
      { source: 'SHOPIFY', identifierType: 'HANDLE', value: product.handle, confirmed: true },
    ],
    priceObservations: [...pdfPriceObservations(pdf), ...familyOdooPriceObservations],
    provenance: [
      sourceRef('WEBSITE_FRONTEND', `products.generated.json ${product.handle}`, raw.title, 'name'),
      sourceRef('WEBSITE_FRONTEND', `catalog.ts productCopy.${product.handle}`, catalog.summary, 'summary'),
      sourceRef('SHOPIFY', `products/${product.handle}.js`, detail.body_html, 'sourceDescription'),
      ...familyOdooProvenance,
      ...(pdf ? [sourceRef('PDF', `ContainerOverzichtRozen.pdf p.1 product ${pdf.productNo}`, pdf.labelRaw, 'dimensions')] : []),
    ],
    conflicts: [
      ...sourceConflicts,
      ...pdfConflicts,
      ...imageConflicts,
      ...weightConflicts,
      ...taxConflicts,
      ...odooWeightConflicts,
      ...descriptionWeightConflicts,
    ],
  };

  return family;
};

const buildOdooFamily = (definition, { review = false, startPosition }) => {
  const rowMappings = definition.rows ?? definition.rowsAsSeparateVariants;
  const variants = rowMappings.map((item, position) => {
    const row = odooByName.get(item.name);
    if (!row) throw new Error(`Missing Odoo row ${item.name}`);
    return makeOdooVariant(
      definition.canonicalFamilyKey,
      row,
      position,
      item.variantColor ?? null,
      item.variantName ?? (item.variantColor ? `${definition.name} — ${item.variantColor}` : row.name),
      item.variantKey ?? item.variantColor ?? row.name,
    );
  });
  const pdf = definition.pdfProductNo == null ? null : pdfByNumber.get(definition.pdfProductNo);
  for (const variant of variants) {
    for (const item of variant.packages) {
      item.operational = !review && !pdf;
      item.confidence = review ? definition.confidence : 'MEDIUM';
    }
  }
  const nameDimensions = rowMappings
    .map((item) => odooNameDimension(odooByName.get(item.name)))
    .filter(Boolean)
    .filter((item, index, items) => items.findIndex((other) => other.rawValue === item.rawValue) === index);
  const variantPackages = variants.flatMap((variant) => variant.packages);
  const packageShapes = new Set(variantPackages.map((item) => JSON.stringify({
    values: item.dimensions.values,
    unit: item.dimensions.unit,
    piecesPerPackage: item.piecesPerPackage,
  })));
  const commonOdooPackage = !pdf
    && variantPackages.length === variants.length
    && packageShapes.size === 1
    ? { ...variantPackages[0], variantCanonicalKey: null }
    : null;
  const reviewConflict = review ? [conflict(
    'UNCERTAIN_CROSS_SOURCE_MATCH',
    `Possible match to website family ${definition.candidateWebsiteHandle}; kept separate pending review.`,
    {
      severity: 'WARNING', confidence: definition.confidence,
      relatedSourceRecords: [definition.candidateWebsiteHandle, ...rowMappings.map((item) => item.name)],
    },
  )] : [];
  const pdfConflict = pdf ? [conflict(
    'PDF_MATCH_REQUIRES_REVIEW',
    `PDF product ${pdf.productNo} is a candidate for this review family; dimensions and prices remain observations, not operational values.`,
    { confidence: definition.confidence, relatedSourceRecords: [`PDF product ${pdf.productNo}`] },
  )] : [];
  const pdfArithmeticConflict = pdf ? [conflict(
    'PDF_PRICE_ARITHMETIC_MISMATCH',
    `Shown unit ${pdf.unitPriceRaw} × ${pdf.quantity} differs from shown total ${pdf.totalRaw}; price context is unknown.`,
    { relatedSourceRecords: [`PDF product ${pdf.productNo}`] },
  )] : [];
  return {
    canonicalFamilyKey: definition.canonicalFamilyKey,
    publicHandle: null,
    active: true,
    cardFeaturedCanonicalVariantKey: null,
    category: null,
    collections: [],
    productPosition: startPosition,
    tags: [],
    requestedPublication: { websiteStatus: 'DRAFT', orderAppStatus: 'DRAFT', catalogueStatus: 'DRAFT' },
    texts: [{
      language: 'EN', name: definition.name, summary: null, description: null,
      format: null, highlights: [], seoTitle: null, seoDescription: null,
    }],
    dimensions: [
      pdfDimension(pdf, { operational: false, confidence: definition.confidence }),
      ...nameDimensions,
    ].filter(Boolean),
    packages: [
      pdfPackage(pdf, { operational: false, confidence: definition.confidence }),
      commonOdooPackage,
    ].filter(Boolean),
    images: [],
    variants,
    externalIdentifiers: rowMappings.map((item) => ({
      source: 'ODOO', identifierType: 'TEMPLATE_NAME', value: item.name, confirmed: true,
    })),
    priceObservations: pdfPriceObservations(pdf),
    provenance: rowMappings.flatMap((item) => odooProvenance(odooByName.get(item.name))),
    conflicts: [...reviewConflict, ...pdfConflict, ...pdfArithmeticConflict],
  };
};

const websiteFamilies = [];
for (const category of catalogModule.catalogCategories) {
  for (let index = 0; index < category.products.length; index += 1) {
    const catalogProduct = category.products[index];
    websiteFamilies.push(await buildWebsiteFamily(rawByHandle.get(catalogProduct.handle), index));
  }
}

const reviewFamilies = mapping.reviewFamilies.map((definition, index) => buildOdooFamily(definition, {
  review: true, startPosition: websiteFamilies.length + index,
}));
const odooOnlyFamilies = mapping.odooOnlyFamilies.map((definition, index) => buildOdooFamily(definition, {
  review: false, startPosition: websiteFamilies.length + reviewFamilies.length + index,
}));
const families = [...websiteFamilies, ...reviewFamilies, ...odooOnlyFamilies];

const mappedOdooRows = new Set();
for (const match of mapping.highConfidenceMatches) {
  for (const row of match.odooRows) mappedOdooRows.add(row.name);
  for (const row of match.familyOnlyOdooRows ?? []) mappedOdooRows.add(row);
}
for (const family of [...mapping.reviewFamilies, ...mapping.odooOnlyFamilies]) {
  for (const row of family.rows ?? family.rowsAsSeparateVariants) mappedOdooRows.add(row.name);
}
for (const excluded of mapping.excludedOdooRows) mappedOdooRows.add(excluded.name);
const allOdooNames = sourceInventory.sources.odooWorkbook.rows.map((row) => row.name);
const missingOdooRows = allOdooNames.filter((name) => !mappedOdooRows.has(name));
if (missingOdooRows.length > 0 || mappedOdooRows.size !== allOdooNames.length) {
  throw new Error(`Odoo coverage mismatch. Missing: ${missingOdooRows.join(', ')}`);
}

const variantCount = families.reduce((sum, family) => sum + family.variants.length, 0);
const logicalImageCount = families.reduce((sum, family) => sum + family.images.length, 0);
if (families.length !== 24 || variantCount !== 58 || logicalImageCount !== 80) {
  throw new Error(`Unexpected canonical counts: ${families.length} families, ${variantCount} variants, ${logicalImageCount} images`);
}

const sourceFiles = [
  ['ODOO_XLSX', odooWorkbookPath],
  ['PDF', pdfPath],
  ['WEBSITE_FRONTEND_JSON', path.join(websiteRepo, 'src/data/products.generated.json')],
  ['WEBSITE_PRESENTATION', path.join(websiteRepo, 'src/data/catalog.ts')],
  ['SHOPIFY_PRODUCTS', shopifyProductsPath],
  ['SHOPIFY_CART_CURRENCY', shopifyCartPath],
  ['SOURCE_AUDIT', sourceAuditPath],
  ['MAPPING_DECISIONS', mappingPath],
];
const sources = [];
for (const [sourceType, file] of sourceFiles) {
  sources.push({ sourceType, filename: path.basename(file), sha256: await hashFile(file) });
}
const detailHashes = [];
for (const product of generatedProducts.products) {
  detailHashes.push(await hashFile(path.join(detailDirectory, `${product.handle}.json`)));
}
sources.push({
  sourceType: 'SHOPIFY_PRODUCT_DETAILS', filename: 'shopify-product-details/*.json',
  sha256: sha256(Buffer.from(detailHashes.sort().join('\n'))),
});
const categories = [...categoryByHandle.values()];
const validationSummary = {
    familyCount: families.length,
    websitePublishedFamilyCount: websiteFamilies.length,
    reviewFamilyCount: reviewFamilies.length,
    odooOnlyFamilyCount: odooOnlyFamilies.length,
    variantCount,
    logicalImageCount,
    imageRenditionCount: logicalImageCount * 2,
    uniqueRenditionBlobCount: new Set(families.flatMap((family) => family.images)
      .flatMap((image) => [image.small.sha256, image.large.sha256])).size,
    shopifyAltTextImages: families.flatMap((family) => family.images)
      .filter((image) => image.altTextSource === 'SHOPIFY').length,
    generatedFallbackAltTextImages: families.flatMap((family) => family.images)
      .filter((image) => image.altTextSource === 'WEBSITE_GENERATED').length,
    odooRows: allOdooNames.length,
    excludedNonProductOdooRows: mapping.excludedOdooRows.length,
    conflicts: families.reduce((sum, family) => sum + family.conflicts.length, 0),
    inventoryKnownVariants: families.flatMap((family) => family.variants).filter((variant) => variant.inventoryKnown).length,
    inventoryUnknownVariants: families.flatMap((family) => family.variants).filter((variant) => !variant.inventoryKnown).length,
    inventoryKnownStockTotal: families.flatMap((family) => family.variants)
      .filter((variant) => variant.inventoryKnown)
      .reduce((sum, variant) => sum + variant.stockQuantity, 0),
    shopifyProductIdentifiers: families.flatMap((family) => family.externalIdentifiers)
      .filter((identifier) => identifier.source === 'SHOPIFY' && identifier.identifierType === 'PRODUCT_ID').length,
    shopifyVariantIdentifiers: families.flatMap((family) => family.variants)
      .flatMap((variant) => variant.externalIdentifiers)
      .filter((identifier) => identifier.source === 'SHOPIFY' && identifier.identifierType === 'VARIANT_ID').length,
    packagingGtinCandidates: families.flatMap((family) => family.variants)
      .flatMap((variant) => variant.externalIdentifiers)
      .filter((identifier) => identifier.identifierType === 'PACKAGING_GTIN_CANDIDATE').length,
    pdfDimensionObservations: families.flatMap((family) => family.dimensions)
      .filter((dimension) => dimension.sourceType === 'PDF').length,
    pdfPackageObservations: families.flatMap((family) => family.packages)
      .filter((item) => item.sourceType === 'PDF').length,
    priceObservationCounts: Object.fromEntries(
      Object.entries(Object.groupBy(
        families.flatMap((family) => [
          ...family.priceObservations,
          ...family.variants.flatMap((variant) => variant.priceObservations),
        ]),
        (observation) => observation.priceType,
      )).map(([priceType, observations]) => [priceType, observations.length]),
    ),
};
const payloadFamilies = structuredClone(families);
for (const family of payloadFamilies) {
  for (const image of family.images) {
    delete image.small.bytesBase64;
    delete image.large.bytesBase64;
  }
}
const transformVersion = '2026-08-20.5';
const sourceDigest = sha256(Buffer.from(sources.map((source) => `${source.sourceType}:${source.sha256}`).join('\n')));
const payloadSha256 = sha256(Buffer.from(JSON.stringify({
  schemaVersion: '1.0',
  categories,
  families: payloadFamilies,
  validationSummary,
})));
const importKey = `enrosed-catalog-${payloadSha256.slice(0, 16)}`;
const manifest = {
  schemaVersion: '1.0',
  importDescriptor: {
    importKey,
    generatedAt: new Date().toISOString(),
    transformVersion,
    sourceDigest,
    payloadSha256,
    sourcePriority: ['ODOO_XLSX_COMMERCIAL', 'PDF_DIMENSIONS_PACKAGING', 'WEBSITE_FRONTEND_PRESENTATION', 'SHOPIFY_LIVE_METADATA'],
    sources,
  },
  categories,
  families,
  validationSummary,
};

const auditManifest = structuredClone(manifest);
for (const family of auditManifest.families) {
  for (const image of family.images) {
    delete image.small.bytesBase64;
    delete image.large.bytesBase64;
  }
}

await Promise.all([
  writeFile(path.join(outputDirectory, 'canonical-catalog.json'), `${JSON.stringify(auditManifest, null, 2)}\n`),
  writeFile(path.join(outputDirectory, 'canonical-catalog-import.json'), `${JSON.stringify(manifest)}\n`),
  writeFile(path.join(outputDirectory, 'migration-summary.json'), `${JSON.stringify(manifest.validationSummary, null, 2)}\n`),
]);

process.stdout.write(`${JSON.stringify({ importKey, ...manifest.validationSummary }, null, 2)}\n`);
