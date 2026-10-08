// Railway settings for the backend service, applied by hand:
//
//   railway config plan     compare this file with the linked environment
//   railway config apply    write the differences to that environment
//
// Railway does not read this file during a deploy. A deploy runs with the
// settings last applied, so apply to an environment before pushing a commit
// that depends on a change here.
//
// A service declared here is owned as a whole: a variable, source or setting
// that this file leaves out is planned for removal. Add the name of a new
// variable below before the next apply; a plan that lists "Delete variable"
// means this file is behind Railway, not that the variable should go.
import { defineRailway, github, preserve, project, service } from "railway/iac";

// Only the backend service belongs to this repository. Postgres, its volume
// and the backup bucket are not declared, and a named partial leaves alone
// what it does not own.
export const partial = "enrosed-erp-backend";

// Names only. preserve() keeps the value stored in Railway; no value is ever
// written in this file.
const VARIABLES = [
  "BREVO_API_KEY",
  "CORS_ORIGINS",
  "ENROSED_MAIL_INTERNAL_RECIPIENT",
  "GOOGLE_ANALYTICS_PROPERTY_ID",
  "GOOGLE_REPORTING_USER_CREDENTIALS_JSON",
  "GOOGLE_SEARCH_CONSOLE_SITE_URL",
  "PGDATABASE",
  "PGHOST",
  "PGPASSWORD",
  "PGPORT",
  "PGUSER",
  "PORTAL_BASE_URL",
  "PUBLIC_FORM_HMAC_SECRET",
  "SMTP_FROM",
  "SMTP_HOST",
  "SMTP_PASSWORD",
  "SMTP_PORT",
  "SMTP_USERNAME",
  "TRUST_RAILWAY_X_REAL_IP",
  "TURNSTILE_HOSTNAMES",
  "TURNSTILE_SECRET",
  "TURNSTILE_SITE_KEY",
  "VERCEL_WEBSITE_DEPLOY_HOOK_URL",
  "WEBSITE_PUBLIC_REVISION_URL",
];

const TEST_ONLY_VARIABLES = [
  "CCFI_AUTOMATED_ACCESS_AUTHORIZED",
  "DAILY_AGENDA_PUSH_CRON",
  "DB_SCHEMA_STRATEGY",
  "DREWRY_AUTOMATED_ACCESS_AUTHORIZED",
  "ENROSED_PUSH_ENABLED",
  "FBX_AUTOMATED_ACCESS_AUTHORIZED",
  "MARKET_DATA_REFRESH_CRON",
  "NCFI_AUTOMATED_ACCESS_AUTHORIZED",
  "NCFI_COMPOSITE_AUTOMATED_ACCESS_AUTHORIZED",
  "QUARKUS_MAILER_MOCK",
  "QUARKUS_SCHEDULER_ENABLED",
  "WEBSITE_BASE_URL",
];

export default defineRailway((ctx) => {
  const production = ctx.environment === "production";
  if (!production && ctx.environment !== "test") {
    // Railway CLI 5.44 passes no environment at all; this file was checked with 5.63.
    throw new Error(
      `No backend settings are defined for the "${ctx.environment}" environment. ` +
        "Link the directory to test or production and use a current Railway CLI.",
    );
  }
  const variables = production ? VARIABLES : [...VARIABLES, ...TEST_ONLY_VARIABLES];

  const backend = service("enrosed-erp-backend", {
    source: github("EntegoBV/enrosed-erp-backend", {
      branch: production ? "main" : "test",
      checkSuites: false,
    }),
    replicas: { ams: 1 },
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "Dockerfile",
    },
    deploy: {
      ipv6EgressEnabled: true,
      // The test environment validates the schema on startup, so the release
      // must not start before these migrations have run.
      preDeployCommand: ["/app/scripts/run-postgresql-schema-migrations.sh"],
      healthcheckPath: "/api/public/terms",
      healthcheckTimeout: 300,
      restartPolicyType: "ALWAYS",
    },
    env: Object.fromEntries(variables.map((name) => [name, preserve()])),
  });

  return project("vibrant-gentleness", {
    resources: [backend],
  });
});
