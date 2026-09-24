# Bewaarde catalogusvolgorde

`catalog-export-order-postgresql.sql` voegt één zelfstandige instellingentabel toe.
De Docker-image bevat de migratie; `run-postgresql-schema-migrations.sh` voert die
voor het opstarten uit. De migratie is herhaalbaar en overschrijft geen bewaarde
volgorde. Er zijn geen afhankelijkheden op andere nieuwe catalogusmigraties.

Beheerders lezen `GET /api/catalog/order` en bewaren met
`PUT /api/catalog/order`, bijvoorbeeld:

```json
{"revision": 0, "orderedIds": [103, 104, 105, 106]}
```

Beide antwoorden bevatten `revision`, `orderedIds` en `updatedAt`, met
`Cache-Control: no-store`. Voor de eerste opslag zijn dit `0`, `[]` en `null`.
Elke geslaagde opslag verhoogt de revisie. Een verouderde revisie geeft HTTP 409,
zodat een ander tabblad of een andere beheerder de recentste volgorde niet stil
overschrijft. Ongeldige, dubbele of niet-positieve IDs geven HTTP 400; maximaal
10.000 IDs zijn toegestaan. Verwijderde producten en demo's worden bij lezen en
bewaren overgeslagen. Een lege lijst wist de bewaarde voorkeursvolgorde.

Deze instelling wordt per ERP-omgeving gedeeld. De catalogusbouwer past de
opgehaalde volgorde toe, voegt nieuwe producten achteraan toe en verstuurt zoals
voorheen expliciete product-ID's bij export. Selectie, exportopties en de
merchandisingvolgorde van producten/families blijven aparte instellingen.
