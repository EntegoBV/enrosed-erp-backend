import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const manifestPath = path.resolve(process.argv[2] ?? 'generated/canonical-catalog-import.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const errors = [];
const assert = (condition, message) => { if (!condition) errors.push(message); };
const unique = (values, label, { allowNull = false } = {}) => {
  const seen = new Set();
  for (const value of values) {
    if (value == null && allowNull) continue;
    assert(value != null && value !== '', `${label} contains a blank value`);
    assert(!seen.has(value), `${label} contains duplicate ${value}`);
    seen.add(value);
  }
};

assert(manifest.schemaVersion === '1.0', 'schemaVersion must be 1.0');
assert(/^\d{4}-\d{2}-\d{2}\.\d+$/.test(manifest.importDescriptor?.transformVersion),
  'importDescriptor.transformVersion is required');
const computedSourceDigest = createHash('sha256').update(
  manifest.importDescriptor.sources
    .map((source) => `${source.sourceType}:${source.sha256}`)
    .join('\n'),
).digest('hex');
assert(manifest.importDescriptor?.sourceDigest === computedSourceDigest,
  'importDescriptor.sourceDigest does not match source descriptors');
assert(Array.isArray(manifest.families), 'families must be an array');
unique(manifest.families.map((family) => family.canonicalFamilyKey), 'family keys');
unique(manifest.families.map((family) => family.publicHandle), 'public handles', { allowNull: true });

const variants = manifest.families.flatMap((family) => family.variants);
const variantByKey = new Map(variants.map((variant) => [variant.canonicalVariantKey, variant]));
const strictFeaturedContract = manifest.importDescriptor.transformVersion >= '2026-08-20.5';
unique(variants.map((variant) => variant.canonicalVariantKey), 'variant keys');
unique(variants.map((variant) => variant.sku), 'generated SKUs');
const shopifyVariantIds = variants.flatMap((variant) => variant.externalIdentifiers)
  .filter((identifier) => identifier.source === 'SHOPIFY' && identifier.identifierType === 'VARIANT_ID')
  .map((identifier) => identifier.value);
unique(shopifyVariantIds, 'Shopify variant IDs');

for (const family of manifest.families) {
  assert(family.texts?.some((text) => text.language === 'EN' && text.name), `${family.canonicalFamilyKey}: missing EN name`);
  unique(family.variants.map((variant) => variant.position), `${family.canonicalFamilyKey} variant positions`);
  unique(family.images.map((image) => image.position), `${family.canonicalFamilyKey} image positions`);
  if (family.cardFeaturedCanonicalVariantKey != null) {
    assert(family.variants.some((variant) => (
      variant.canonicalVariantKey === family.cardFeaturedCanonicalVariantKey && variant.active
    )), `${family.canonicalFamilyKey}: card feature must be an active family variant`);
  }
  for (const collection of family.collections ?? []) {
    if (collection.featuredCanonicalVariantKey == null) continue;
    const selected = variantByKey.get(collection.featuredCanonicalVariantKey);
    const selectedFamily = manifest.families.find((candidate) => (
      candidate.variants.some((variant) => variant.canonicalVariantKey === selected?.canonicalVariantKey)
    ));
    assert(selected?.active && selectedFamily?.collections?.some((item) => item.key === collection.key),
      `${collection.key}: featured variant must be active and belong to the collection`);
  }
  if (family.requestedPublication.websiteStatus === 'PUBLISHED') {
    const englishText = family.texts.find((text) => text.language === 'EN');
    assert(family.publicHandle, `${family.canonicalFamilyKey}: published without publicHandle`);
    assert(family.category?.key, `${family.canonicalFamilyKey}: published without category`);
    assert(family.category?.eyebrow?.trim(), `${family.canonicalFamilyKey}: published category without eyebrow`);
    assert(family.category?.description?.trim(), `${family.canonicalFamilyKey}: published category without description`);
    assert(family.collections?.filter((item) => item.primary).length === 1,
      `${family.canonicalFamilyKey}: published family needs exactly one primary collection`);
    assert(englishText?.summary?.trim(), `${family.canonicalFamilyKey}: published without EN summary`);
    assert(englishText?.format?.trim(), `${family.canonicalFamilyKey}: published without EN format`);
    assert(englishText?.seoTitle?.trim(), `${family.canonicalFamilyKey}: published without EN SEO title`);
    assert(englishText?.seoDescription?.trim(), `${family.canonicalFamilyKey}: published without EN SEO description`);
    assert(family.texts.some((text) => text.description?.trim()),
      `${family.canonicalFamilyKey}: published without any stored source description`);
    assert(family.images.length > 0, `${family.canonicalFamilyKey}: published without images`);
    assert(family.variants.length > 0, `${family.canonicalFamilyKey}: published without variants`);
    const pdfDimensions = family.dimensions.filter((item) => item.sourceType === 'PDF');
    const websiteDimensions = family.dimensions.filter((item) => item.sourceType === 'WEBSITE_FRONTEND');
    if (pdfDimensions.length > 0) {
      assert(pdfDimensions.every((item) => item.operational === true),
        `${family.canonicalFamilyKey}: matched PDF dimensions must be operational`);
      assert(websiteDimensions.every((item) => item.operational === false),
        `${family.canonicalFamilyKey}: PDF must remain primary over website dimensions`);
    } else if (websiteDimensions.length > 0) {
      assert(websiteDimensions.every((item) => item.operational === true && item.confidence === 'MEDIUM'),
        `${family.canonicalFamilyKey}: best-available website dimensions must be operational MEDIUM confidence`);
    }
  }

  for (const dimension of family.dimensions) {
    assert(Array.isArray(dimension.values) && dimension.values.length > 0, `${family.canonicalFamilyKey}: empty dimension values`);
    assert(dimension.values.every((value) => Number.isFinite(value) && value > 0), `${family.canonicalFamilyKey}: invalid dimensions`);
  }
  for (const item of family.packages) {
    assert(item.dimensions?.values?.length === 3, `${family.canonicalFamilyKey}: package requires three dimensions`);
    assert(item.dimensions?.values?.every((value) => Number.isFinite(value) && value > 0), `${family.canonicalFamilyKey}: invalid package dimensions`);
    assert(Number.isInteger(item.piecesPerPackage) && item.piecesPerPackage > 0, `${family.canonicalFamilyKey}: invalid pieces per package`);
  }
  for (const item of family.packages.filter((entry) => entry.sourceType === 'PDF')) {
    assert(item.variantCanonicalKey == null,
      `${family.canonicalFamilyKey}: PDF family package must be inherited, not variant-linked`);
    const expectedOperational = family.requestedPublication.websiteStatus === 'PUBLISHED';
    assert(item.operational === expectedOperational,
      `${family.canonicalFamilyKey}: PDF package operational flag must follow approved match status`);
    assert(item.confidence === (expectedOperational ? 'HIGH' : 'MEDIUM'),
      `${family.canonicalFamilyKey}: PDF package confidence must follow approved match status`);
  }
  assert(
    family.variants.flatMap((variant) => variant.packages)
      .every((item) => item.sourceType !== 'PDF'),
    `${family.canonicalFamilyKey}: PDF package must not be duplicated on variants`,
  );
  for (const variant of family.variants) {
    assert(variant.skuProvenance === 'GENERATED_INTERNAL' || variant.sourceSku, `${variant.canonicalVariantKey}: SKU provenance missing`);
    assert(variant.colourHex == null || /^#[0-9A-F]{6}$/.test(variant.colourHex),
      `${variant.canonicalVariantKey}: colourHex must be #RRGGBB or null`);
    if (strictFeaturedContract && variant.active && variant.color != null
        && ['READY', 'PUBLISHED'].includes(family.requestedPublication.websiteStatus)) {
      assert(variant.colourHex != null,
        `${variant.canonicalVariantKey}: website-ready coloured variant requires colourHex`);
    }
    assert(variant.inventoryKnown ? Number.isInteger(variant.stockQuantity) && variant.stockQuantity >= 0 : variant.stockQuantity == null,
      `${variant.canonicalVariantKey}: inventoryKnown/stockQuantity mismatch`);
    for (const observation of variant.priceObservations) {
      assert(observation.amount == null || Number.isFinite(observation.amount), `${variant.canonicalVariantKey}: invalid price amount`);
      assert(observation.currency == null || /^[A-Z]{3}$/.test(observation.currency), `${variant.canonicalVariantKey}: invalid price currency`);
    }
  }
  for (const image of family.images) {
    assert(image.altText?.trim(), `${family.canonicalFamilyKey} image ${image.position}: missing alt text`);
    for (const renditionName of ['small', 'large']) {
      const rendition = image[renditionName];
      assert(rendition?.width > 0 && rendition?.height > 0, `${family.canonicalFamilyKey} image ${image.position}: invalid ${renditionName} dimensions`);
      assert(rendition?.bytesBase64, `${family.canonicalFamilyKey} image ${image.position}: missing ${renditionName} bytes`);
      if (rendition?.bytesBase64) {
        const bytes = Buffer.from(rendition.bytesBase64, 'base64');
        const digest = createHash('sha256').update(bytes).digest('hex');
        assert(digest === rendition.sha256, `${family.canonicalFamilyKey} image ${image.position}: ${renditionName} checksum mismatch`);
        assert(bytes.length <= 26_214_400, `${family.canonicalFamilyKey} image ${image.position}: ${renditionName} exceeds 25 MiB`);
        assert(bytes.subarray(0, 4).toString('ascii') === 'RIFF' && bytes.subarray(8, 12).toString('ascii') === 'WEBP',
          `${family.canonicalFamilyKey} image ${image.position}: ${renditionName} is not WebP`);
      }
    }
  }
}

const summary = manifest.validationSummary;
const images = manifest.families.flatMap((family) => family.images);
const payloadFamilies = structuredClone(manifest.families);
for (const family of payloadFamilies) {
  for (const image of family.images) {
    delete image.small.bytesBase64;
    delete image.large.bytesBase64;
  }
}
const computedPayloadSha256 = createHash('sha256').update(JSON.stringify({
  schemaVersion: manifest.schemaVersion,
  categories: manifest.categories,
  families: payloadFamilies,
  validationSummary: summary,
})).digest('hex');
assert(manifest.importDescriptor?.payloadSha256 === computedPayloadSha256,
  'importDescriptor.payloadSha256 does not match canonical content');
assert(manifest.importDescriptor?.importKey === `enrosed-catalog-${computedPayloadSha256.slice(0, 16)}`,
  'importKey must be derived from canonical payload content');
const allProvenance = manifest.families.flatMap((family) => [
  ...family.provenance,
  ...family.variants.flatMap((variant) => variant.provenance),
]);
const uniqueSourceLocations = (fieldPath, predicate = () => true) => new Set(
  allProvenance
    .filter((item) => item.fieldPath === fieldPath && predicate(item.sourceValue))
    .map((item) => item.sourceLocation),
);
const expectedPdfDimensions = new Map([
  [1, [11, 11]],
  [3, [12, 25]],
  [4, [12, 20]],
  [5, [15, 30]],
  [6, [15, 30]],
  [7, [20, 20]],
  [8, [28, 28]],
  [9, [10, 8]],
  [10, [5.5, 6]],
]);
const actualPdfDimensions = manifest.families.flatMap((family) => family.dimensions)
  .filter((dimension) => dimension.sourceType === 'PDF');
for (const dimension of actualPdfDimensions) {
  const productNumber = Number(dimension.sourceLocation?.match(/product (\d+)$/)?.[1]);
  const expected = expectedPdfDimensions.get(productNumber);
  assert(expected, `unexpected PDF dimension source ${dimension.sourceLocation}`);
  assert(
    expected && JSON.stringify(dimension.values) === JSON.stringify(expected),
    `PDF product ${productNumber} dimensions must be ${expected?.join(' x ')} cm, got ${dimension.values?.join(' x ')}`,
  );
}
assert(summary.familyCount === manifest.families.length, 'summary family count mismatch');
assert(summary.variantCount === variants.length, 'summary variant count mismatch');
assert(summary.logicalImageCount === images.length, 'summary image count mismatch');
assert(summary.imageRenditionCount === images.length * 2, 'summary rendition count mismatch');
assert(summary.uniqueRenditionBlobCount === 156, 'expected 156 checksum-distinct WebP blobs');
assert(summary.inventoryKnownStockTotal === 18_206, 'known inventory must reconcile to 18,206');
assert(summary.shopifyProductIdentifiers === 19, 'expected 19 Shopify product IDs');
assert(summary.shopifyVariantIdentifiers === 47, 'expected 47 Shopify variant IDs');
assert(summary.packagingGtinCandidates === 4, 'expected four candidate packaging GTINs');
assert(summary.pdfDimensionObservations === 9, 'expected nine PDF dimension observations');
assert(actualPdfDimensions.length === expectedPdfDimensions.size, 'expected every audited PDF product dimension once');
assert(summary.pdfPackageObservations === 9, 'expected nine PDF package observations');
assert(uniqueSourceLocations('stockUnit').size === 40, 'expected stock unit provenance for all 40 imported Odoo product rows');
assert(uniqueSourceLocations('forecastQuantity').size === 40,
  'expected forecast provenance for all 40 imported Odoo product rows');
assert(uniqueSourceLocations('structuredCostRaw').size === 40,
  'expected structured cost provenance including explicit zero for all 40 imported Odoo product rows');
assert(uniqueSourceLocations('weight', (value) => Number(value) > 0).size === 18,
  'expected all 18 non-zero Odoo weight observations');
assert(uniqueSourceLocations('weightGrams', (value) => Number(value) > 0).size === 4,
  'expected four Shopify 2000-gram observations');
assert(allProvenance.filter((item) => item.fieldPath === 'weightObservation').length === 10,
  'expected all ten explicit Odoo description weight observations');
assert(allProvenance.filter((item) => item.fieldPath === 'taxable' && item.sourceValue === false).length === 4,
  'expected four Shopify non-taxable observations');
const jewelleryFamily = manifest.families.find((family) => family.canonicalFamilyKey === 'rose-diamonds-within-display');
assert(jewelleryFamily?.packages?.some((item) => (
  JSON.stringify(item.dimensions?.values) === JSON.stringify([43, 32, 37])
  && item.piecesPerPackage === 336
  && item.operational === true
  && item.confidence === 'MEDIUM'
)), 'jewellery rose family must retain the common 43 x 32 x 37 cm / 336-piece carton');
assert(jewelleryFamily?.dimensions?.some((item) => (
  item.dimensionType === 'PRODUCT_DISPLAY'
  && JSON.stringify(item.values) === JSON.stringify([4.8, 4.8])
  && item.operational === true
)), 'jewellery rose family must retain the common 4.8 x 4.8 cm acrylic dimension');
assert(jewelleryFamily?.dimensions?.some((item) => (
  item.dimensionType === 'GIFT_BOX'
  && JSON.stringify(item.values) === JSON.stringify([20.3, 14.8, 4.7])
  && item.operational === false
)), 'jewellery rose family must retain the giftbox dimension as non-operational');
assert(jewelleryFamily?.variants?.every((variant) => variant.packages?.some((item) => (
  JSON.stringify(item.dimensions?.values) === JSON.stringify([43, 32, 37])
  && item.piecesPerPackage === 336
  && item.operational === true
  && item.confidence === 'MEDIUM'
))), 'every jewellery rose colour must retain its Odoo carton observation');
const preservedWindowbox = manifest.families.find((family) => (
  family.canonicalFamilyKey === 'odoo-preserved-rose-windowbox'
));
assert(preservedWindowbox?.packages?.some((item) => (
  JSON.stringify(item.dimensions?.values) === JSON.stringify([35, 47, 35])
  && item.piecesPerPackage === 30
  && item.operational === true
  && item.confidence === 'MEDIUM'
)), 'Odoo-only preserved windowbox must expose its common outer carton at family level');
assert(preservedWindowbox?.variants?.every((variant) => variant.packages?.some((item) => (
  JSON.stringify(item.dimensions?.values) === JSON.stringify([35, 47, 35])
  && item.piecesPerPackage === 30
  && item.operational === true
  && item.confidence === 'MEDIUM'
))), 'Odoo-only preserved windowbox variants must retain an operational MEDIUM carton');
for (const family of manifest.families.filter((item) => (
  item.packages.some((entry) => entry.sourceType === 'PDF' && entry.operational === true)
))) {
  assert(family.variants.flatMap((variant) => variant.packages)
    .filter((item) => item.sourceType === 'ODOO_XLSX')
    .every((item) => item.operational === false && item.confidence === 'MEDIUM'),
  `${family.canonicalFamilyKey}: PDF must remain primary over variant Odoo cartons`);
}
assert(manifest.families.flatMap((family) => family.conflicts)
  .filter((item) => item.code === 'WEIGHT_SOURCE_CONFLICT').length === 1,
  'expected one explicit Odoo/Shopify weight conflict');
assert(manifest.families.flatMap((family) => family.conflicts)
  .filter((item) => item.code === 'PURCHASE_TAX_VARIANT_CONFLICT').length === 2,
  'expected two explicit purchase-tax conflicts across colour variants');
assert(manifest.families.flatMap((family) => family.conflicts)
  .filter((item) => item.code === 'ODOO_WEIGHT_VARIANT_CONFLICT').length === 1,
  'expected the small glass-bowl 0.07 versus 0.7 Odoo weight conflict');
assert(manifest.families.flatMap((family) => family.conflicts)
  .filter((item) => item.code === 'ODOO_STRUCTURED_VS_DESCRIPTION_WEIGHT_CONFLICT').length === 2,
  'expected small and XL glass-bowl structured-versus-description weight conflicts');
assert(manifest.families.flatMap((family) => family.conflicts)
  .filter((item) => item.code === 'PDF_PRICE_ARITHMETIC_MISMATCH').length === 9,
  'expected one arithmetic price conflict for every PDF product row');
assert(summary.priceObservationCounts?.NARRATIVE_UNCLASSIFIED === 4,
  'expected four standalone unclassified Odoo price observations');
const francePrices = manifest.families.flatMap((family) => [
  ...family.priceObservations,
  ...family.variants.flatMap((variant) => variant.priceObservations),
]).filter((item) => item.priceType === 'FRANCE_SALES');
assert(francePrices.filter((item) => item.currency === 'EUR' && item.amount === 3.48).length === 1,
  'Rose in mirrorbox France price must retain its explicit EUR currency');
assert(francePrices.filter((item) => item.currency == null).length === 9,
  'the other nine France price observations must keep unknown currency');

if (errors.length > 0) {
  process.stderr.write(`${errors.map((error) => `- ${error}`).join('\n')}\n`);
  process.exitCode = 1;
} else {
  process.stdout.write(`${JSON.stringify({ valid: true, importKey: manifest.importDescriptor.importKey, ...summary }, null, 2)}\n`);
}
