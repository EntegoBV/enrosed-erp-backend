# Website rebuild scheduler

`WebsiteRebuildService` gebruikt een duurzame database-outbox. Opslaan zet een revisie op
`QUEUED`; de scheduler-worker verstuurt daarna pas de Vercel deploy hook. Wanneer de globale
Quarkus scheduler uitstaat, kan die rij dus nooit verdergaan.

## Veilige TEST-configuratie

```text
QUARKUS_SCHEDULER_ENABLED=true
DAILY_AGENDA_PUSH_CRON=off
MARKET_DATA_REFRESH_CRON=off
```

Hiermee blijven uitsluitend de interne website-rebuildtimers geregistreerd:

- `website-rebuild-worker` — verwerkt de outbox elke 10 seconden;
- `website-rebuild-live-revision-poller` — controleert elke 60 seconden de live revisie.

De telefoonmelding `daily-agenda-push` en de externe
`freight-market-daily-refresh` worden met Quarkus' ondersteunde cronwaarde `off` helemaal
niet als trigger geregistreerd. Alleen de globale scheduler uitschakelen is daarom geen veilige
manier om één job te stoppen: dat stopt ook de website-outbox.

## Productie

De bestaande productieplanning blijft de standaard. Ze kan per job worden gewijzigd of met
`off` worden uitgeschakeld zonder de website-worker te stoppen:

Railway-waarden, zonder aanhalingstekens:

```text
QUARKUS_SCHEDULER_ENABLED=true
DAILY_AGENDA_PUSH_CRON=0 0 9 * * ?
MARKET_DATA_REFRESH_CRON=0 15 3 * * ?
```

Agenda gebruikt `Europe/Brussels`; marktdata gebruikt UTC. Laat een cron op `off` wanneer die
omgeving de bijbehorende externe actie niet mag uitvoeren.

In TEST: wijzig eerst de job-specifieke crons naar `off` en zet pas daarna de globale scheduler
aan. Controleer na
de Railway-restart via de website-rebuildstatus dat `QUEUED` naar `TRIGGERED` en daarna `LIVE`
gaat. De deploy-hook-URL blijft geheim en hoort niet in logs of deze documentatie.
