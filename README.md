# Enrosed — Sales & Sourcing (backend)

Quarkus 3.38 op Java 25. De frontend staat in
[enrosed-erp-frontend](https://github.com/EntegoBV/enrosed-erp-frontend).

Dit bestand documenteert het hele systeem: de rekenregels, de offerteflow, de talen en de
BTW-behandeling zitten allemaal aan deze kant, dus staat de uitleg hier.

- **Inkoop** — per container bij een leverancier in China. Hier wordt de **kostprijs per
  stuk** berekend: EXW in dollar of RMB, lokale kosten in China, zeevracht, invoerrechten
  per HS-code en de kosten vanaf Rotterdam.
- **Verkoop** — per land, **op pallets** over de weg, met staffelkorting en minimum
  orderwaarde. De offerte gaat als PDF naar de klant, die ze online kan tekenen of een
  wijziging kan voorstellen.

Mobile first: telefoon krijgt een tabbalk onderaan, desktop een zijbalk vanaf 1024 px.

## Starten

Twee repositories, twee processen. **Eerst de backend**, anders heeft de frontend niets om
mee te praten.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn quarkus:dev
```

Daarna in de frontend-repository:

```bash
npm start
```

| | |
|---|---|
| App | <http://localhost:4321> |
| API | <http://localhost:8080> |
| Swagger UI | <http://localhost:8080/q/swagger-ui> |

**Aanmelden:** `enrosedadmin` / het wachtwoord dat je hebt doorgegeven.

## Aanmelden en beveiliging

Quarkus Security bewaakt de hele API. `deny-unannotated-endpoints` staat aan: een nieuw
endpoint zonder annotatie is **dicht**, niet open — vergeten is dan onhandig in plaats van
gevaarlijk. Alle beheerresources dragen `@RolesAllowed("admin")`; alleen het klantportaal
staat met `@PermitAll` open, want daar heeft de klant geen account maar een token in zijn
link.

Het wachtwoord staat als **bcrypt-hash** in `application.properties`, niet als leesbare
tekst. Wie de repository in handen krijgt heeft daarmee nog geen toegang.

Op een server hoort die hash er helemaal niet in te staan. Gebruik daarvoor
omgevingsvariabelen; de waarden in het bestand zijn alleen de standaard voor lokaal werken:

```bash
ENROSED_ADMIN_USERNAME=...
ENROSED_ADMIN_PASSWORD_HASH=...
DB_USERNAME=...
DB_PASSWORD=...
```

Een nieuw wachtwoord zetten:

```java
BcryptUtil.bcryptHash("nieuw wachtwoord")   // resultaat in enrosed.admin.password-hash
```

De frontend stuurt HTTP Basic mee via een interceptor en bewaart de sleutel in
`sessionStorage` — bij het sluiten van het tabblad is de sessie voorbij, wat op een
gedeelde beurscomputer het verschil maakt.

Een `<img src>` kan geen Authorization-header meesturen, dus foto's worden met de
HttpClient opgehaald en als blob-URL getoond (`AuthImage`). Die URL wordt weer vrijgegeven
zodra het element verdwijnt.

**Wat dit nog niet is:** één account, één rol. Zodra er meerdere mensen met verschillende
rechten bijkomen hoort daar een gebruikerstabel of OIDC te staan; de rest van de
beveiliging verandert daar niet van, want die hangt aan de rol. Basic auth hoort ook
alleen over HTTPS de deur uit — op localhost is het prima, op een server niet zonder TLS.

## De offerteflow

```
CONCEPT ──versturen──> VERZONDEN ──klant opent──> BEKEKEN ──tekent──> GEACCEPTEERD
                                       │
                                       ├──wijst af──────────────────> AFGEWEZEN
                                       │
                                       └──stelt wijziging voor──> WIJZIGING_GEVRAAGD
                                                                          │
                                    wij nemen over ──> CONCEPT (bijsturen, opnieuw sturen)
                                    wij wijzen af  ──> VERZONDEN

AFGEWEZEN of VERLOPEN ──heropenen──> CONCEPT
```

**Afgewezen is geen eindpunt.** Een "nee" betekent meestal dat het te duur was of dat de
levertermijn niet uitkwam. *Heropenen* zet de offerte terug op concept met hetzelfde nummer,
dezelfde geschiedenis en dezelfde portallink, en een verse geldigheidsdatum. De beslissing en
de handtekening worden gewist: die hoorden bij de vorige ronde. Een **aanvaarde** offerte kan
niet heropend worden — daar is voor getekend, en die openbreken maakt onduidelijk waar de
handtekening bij hoort. Daarvoor maak je een nieuwe.

Versturen maakt de PDF, mailt hem naar de klant met een portallink, en zet de offerte op
verzonden. In ontwikkeling staat `quarkus.mailer.mock=true`: er vertrekt niets, de mail
komt in de log — zo kan je de flow testen zonder dat er post naar een echte klant gaat.

In het portaal kan de klant de aantallen aanpassen **en producten bijbestellen** uit het
volledige assortiment, aan de prijzen en kortingen van zijn eigen offerte. Bijbestellen gaat
via een **zoekvenster**, niet via een uitgeklapte lijst van alles wat we verkopen: met
tientallen artikelen scrolt niemand daar doorheen. Bij elk artikel staat de voorraadstip, en
bij een artikel zonder voorraad meteen *levertermijn nog te bepalen*.

Aantallen van de klant worden **server-side op volle dozen afgerond**, net als de onze. Het
scherm toont de correctie meteen en past ze na twee seconden toe; de server rondt bij het
opslaan nog een keer af. Het scherm is de beleefdheid, die controle is de garantie — een
klant kan het scherm omzeilen, en "13 stuks" op een order klopt verderop nergens meer.

Zolang zijn voorstel bij ons ligt, ziet de klant dat staan in plaats van een tekenknop:
tekenen zou betekenen dat hij tekent voor de offerte zoals ze wás. Hij kan zijn voorstel
**intrekken** — dat wist het niet, het zet het op ingetrokken en is zelf een stap in de
geschiedenis.

**De klant wijzigt de offerte niet zelf.** Hij legt een voorstel neer dat bij ons ter
beoordeling komt; de offerte zelf blijft staan zoals ze verstuurd is. Een verzonden
document mag niet onder onze handen veranderen, anders weet niemand meer welke versie
getekend is. Nemen we het voorstel over, dan gaan de aantallen naar de order en valt de
offerte terug op concept zodat we prijzen of korting nog kunnen bijsturen.

Een voorstel beoordeel je met drie knoppen, niet twee. **Overnemen** neemt de aantallen
ongewijzigd over; **Wijzigen** doet hetzelfde maar rolt door naar de regels zodat je meteen
kan bijsturen; **Afwijzen** laat de offerte staan zoals ze was. "Akkoord" en "akkoord mits"
zijn twee verschillende antwoorden en verdienen twee knoppen.

### Geschiedenis

Elke stap wordt vastgelegd: opgemaakt, verstuurd, voor het eerst geopend, voorstel,
ingetrokken, overgenomen, afgewezen, getekend, heropend, levertermijn ingevuld, vracht
ingevuld. Klik op het **statusblok** bovenaan een offerte om ze uit te klappen — dat is waar
je kijkt als je je afvraagt hoe deze offerte hier gekomen is, en het scheelt een knop op een
scherm dat er al veel heeft.

Gebeurtenissen worden alleen toegevoegd, nooit gewijzigd of verwijderd. Ook een ingetrokken
voorstel blijft staan: dat het er even gestaan heeft hoort bij het verhaal. Alleen de
**eerste** opening komt erin — elke keer vastleggen maakt er een logboek van waarin het
echte nieuws verdwijnt; het aantal keer staat al op de order.

Op de order zie je **hoe vaak** en **wanneer** de klant hem geopend heeft — dat een klant er
drie keer op terugkomt zegt iets voor je belt. Daarnaast kan je er **interne notities** bij
zetten; die blijven binnen en komen niet op de offerte of in het portaal.

Tekenen is de ingetikte naam plus tijdstip. Voor een juridisch zwaardere handtekening
hoort daar een eID- of gekwalificeerde-handtekeningdienst tussen; dat zit hier niet in.

Het klantportaal krijgt een **eigen weergave** van de server, geen versie van ons scherm
met velden verborgen: kostprijs, marge en de gemengde-palletberekening zitten niet in het
antwoord.

## E-mail werkend krijgen

**Standaard vertrekt er geen mail.** In `backend/src/main/resources/application.properties`
staat `quarkus.mailer.mock=true`: de offerte wordt opgebouwd, de PDF wordt gemaakt, maar er
gaat niets de deur uit. Je ziet dan een waarschuwing in de serverlog. Dat is met opzet zo,
zodat je de flow kan testen zonder per ongeluk post naar een echte klant te sturen.

Echt versturen doe je in vier regels:

```properties
quarkus.mailer.mock=false
quarkus.mailer.host=smtp.gmail.com
quarkus.mailer.port=587
quarkus.mailer.start-tls=REQUIRED
quarkus.mailer.username=offertes@enrosed.be
quarkus.mailer.password=<app-wachtwoord>
quarkus.mailer.from=offertes@enrosed.be
```

Voor **Gmail of Google Workspace** heb je een *app-wachtwoord* nodig, niet je gewone
wachtwoord: aan te maken in je Google-account onder Beveiliging → App-wachtwoorden, en dat
werkt alleen met tweestapsverificatie aan. Voor **Microsoft 365** is het
`smtp.office365.com`, poort 587. Bij eigen hosting vraag je host, poort en gebruikersnaam
aan je provider.

Het adres bij `from` moet een adres zijn dat je echt mag gebruiken — anders weigert de
ontvangende server de mail of belandt hij bij de spam.

## Voorraad

De voorraad hangt aan de inkoop. Zet een inkooporder op **Ontvangen** en de aantallen
worden bijgeboekt — één keer, niet opnieuw bij elke bewaring. De stand staat in de
catalogus en op de regels van de inkooporder.

Aantallen tik je in **stuks**, want zo praat je met een leverancier. De server rondt naar
boven af op een volle doos: vraag je 5 van iets dat per 6 verpakt zit, dan komen er 6, met
een melding erbij. Half gevulde dozen bestaan niet, en een order die dat wel veronderstelt
klopt verderop nergens meer — niet in het volume, niet in de vracht, niet in de kostprijs.

## Inkoopcijfers verbergen — dubbelklik op het logo

**Dubbelklik op het logo** (zijbalk op desktop, bovenbalk op telefoon) en kostprijs, marge
en inkooptotalen verschijnen of verdwijnen, overal tegelijk. Op een touchscreen werkt
**dubbeltikken**: browsers sturen daar geen betrouwbare `dblclick`, dus meet het component
zelf de tijd tussen twee tikken (400 ms).

Staat het aan, dan wordt **de hele app rozerood** in plaats van groen — logo, knoppen,
tabbalk. Dat zie je van de andere kant van een beursstand, terwijl je voor een omgekeerd
logo op het scherm moet turen.

Groen is dus de **veilige** stand en rood de waarschuwing. Precies andersom als je aan
"goedgekeurd" denkt, maar het gaat hier niet om goed of fout: het gaat om wie er meekijkt.

Dezelfde schakelaar bepaalt ook wat er op de **inkoop-PDF** komt (zie hieronder). Eén stand
voor scherm én papier, want twee losse instellingen betekent vroeg of laat dat je het scherm
afdekt en toch het verkeerde blad uitprint.

Bewust geen knop met "inkoop verbergen": zo'n knop verraadt precies dat er iets te
verbergen valt, en op een beurs staat de klant naast je. **Standaard staat het uit.**
Interne blokken die wél getoond worden zijn geel gemarkeerd met het label *intern*.

## BTW en intracommunautaire leveringen

Wij zitten in België, dus het regime volgt uit de bestemming en het BTW-nummer van de klant:

| Bestemming | Regime | BTW |
|---|---|---|
| België | binnenland | 21 % |
| EU-lidstaat, klant mét BTW-nummer | intracommunautaire levering | 0 %, heffing verlegd |
| EU-lidstaat, klant zónder BTW-nummer | geen vrijstelling | tarief van dat land |
| Buiten de EU (bv. VK, Zwitserland) | uitvoer | 0 % |

De bijhorende wettelijke vermelding komt automatisch op de offerte-PDF en in het
klantportaal:

- intracommunautair — *art. 39bis W.BTW / art. 138 Richtlijn 2006/112/EG, BTW te voldoen
  door de afnemer*
- uitvoer — *art. 39 W.BTW / art. 146 Richtlijn 2006/112/EG*

Per land staat een vinkje **EU-lidstaat** bij *Landen & vracht*; het Verenigd Koninkrijk en
Zwitserland staan uit.

**Laat die zinnen een keer nakijken door je boekhouder.** De app zorgt dat de vermelding er
staat en dat het tarief klopt met het regime; of de formulering juist is voor jouw concrete
leveringen is een vraag voor een accountant, niet voor software. Het BTW-nummer wordt hier
alleen op vorm gecontroleerd, niet bij VIES opgevraagd — voor een echte vrijstelling hoort
dat nummer geldig verklaard te worden.

## Aantallen: stuks in, volle dozen uit

Je tikt overal **stuks** in, op verkoop- én inkooporders. Vraag je er 5 van iets dat per 6
verpakt zit, dan verschijnt **meteen** de melding *wordt zo 6*, en springt het veld
**twee seconden later** ook echt naar 6. Die twee seconden zijn er omdat je anders niet kan
typen: wie 240 intikt is na de eerste toets al bij "2", en een veld dat dan meteen naar 6
springt is onbruikbaar. Half gevulde dozen bestaan niet, en een order die dat wel
veronderstelt klopt verderop nergens meer — niet in het volume, niet in de pallets, niet in
de vracht. Voeg je toe terwijl de wachttijd nog loopt, dan wordt er alsnog afgerond.

De productkiezer is een **zoekveld**, geen keuzelijst: tik een paar letters, een SKU of een
barcode en je ziet foto, doosinhoud, voorraad en prijs. Een dropdown met tientallen
artikelen is op een telefoon onwerkbaar.

## Catalogus exporteren

*Catalogus → Catalogus PDF*: vink aan wat erin moet, met of zonder prijzen, hoeveel foto's
per product, en geef het een titel en inleiding. Zonder prijzen krijg je een productblad dat
je aan iedereen kan geven.

De opmaak is een beurscatalogus: twee kaarten per rij, hoofdfoto groot met de bijbeelden
eronder, en smalle marges — papier is duur en op een stand blader je met een klant mee. Je
bedrijfsgegevens en logo staan in de kop.

De knop **PDF maken** staat in de balk onderaan. Die balk blijft nu ook op desktop staan;
hem daar verbergen betekende dat de hoofdactie van een pagina nergens stond.

## ERP als bron voor website en bestelapp

Een product blijft de voorraad dragende SKU. Verwante kleuren of uitvoeringen kunnen via
de optionele `familyKey` als merchandisingfamilie bij elkaar horen. De `publicHandle` is
de unieke, stabiele URL-identiteit; hij wordt niet uit een veranderlijke productnaam afgeleid.

Website en bestelapp hebben elk hun eigen status: `DRAFT`, `READY` of `PUBLISHED`. Nieuwe
en bestaande producten beginnen veilig als `DRAFT`. Bij het product levert de beheer-API
`publicationIssues` terug met concrete Nederlandse aandachtspunten. Publiceren wordt door
de server geweigerd zolang het product niet actief is of SKU, naam, categorie, beschrijving,
foto, verkoopprijs, geldige omdoos of publieke handle ontbreken.

De openbare, alleen-lezen contracten zijn:

- `GET /api/v1/public/catalog?channel=WEBSITE&language=EN` — taal valt terug op `NL`;
- `GET /api/v1/public/catalog/products/{productId}/photos/{photoId}` — alleen voor een
  product dat op minstens één publiek kanaal gepubliceerd staat.

De catalogus geeft uitsluitend actieve, voor dat kanaal gepubliceerde SKU's terug. Het is
een aparte DTO en bevat dus nooit leverancier, EXW, landed cost, opslag/marge, HS-code,
interne bron of exacte voorraad. Beschikbaarheid is alleen `IN_STOCK` of
`AVAILABLE_ON_ORDER`. Naam, beschrijving en kleur zijn al in de gevraagde taal opgelost;
consumenten hoeven de interne vertalingstabel niet te kennen.

De masterdata-CSV bevat achteraan ook `family_key`, `public_handle`, `website_status` en
`order_app_status`. Oude bestanden zonder die vier kolommen blijven bruikbaar: lege of
ontbrekende cellen laten de huidige waarde staan.

## Levertermijn per regel

Elke verkooporderregel krijgt automatisch een leverdatum: vanaf de eerstvolgende **werkdag**
plus de transittijd van het bestemmingsland. Weekends tellen niet mee.

Ligt er niet genoeg op voorraad, dan komt er **geen datum** uit — een schatting verzinnen
voor iets dat nog uit China moet komen is erger dan geen schatting, want de klant rekent
erop. Je vult dan zelf een leverweek in (`2026-W42`), of laat het leeg tot de container
geboekt is. Nooit verplicht.

Elke regel heeft rechts een **wijzigknopje** (✎) dat het leverblok openklapt — ook bij een
groene regel. De berekende datum is een schatting op basis van de transittijd, geen belofte;
soms weet jij beter wanneer die container binnen is.

De leverweek kies je met een **kalender**: je prikt een dag en het veld maakt er de
ISO-week van, met *week 42 · 2026* en *van 12/10/2026 tot 18/10/2026* eronder. Een
`<input type="week">` leek voor de hand te liggen, maar Safari en Firefox kennen die niet
en tonen dan een kaal tekstvak — net op de telefoon.

Op de offerte-PDF staat het per regel in een eigen kolom: *vanaf 19/08/2026*, *week 42
(12/10 - 18/10/2026)*, of *in overleg*. In het klantportaal ziet de klant hetzelfde.

### Rood betekent: terug naar ons

De status per regel is **groen of rood**, niet meer dan dat. Groen is een datum of een week.
Rood is *levertermijn nog te bepalen*, en dan hoort de offerte terug bij ons.

De order onthoudt waar dat heen en weer staat (`deliveryTerms`):

| Stand | Betekenis |
| --- | --- |
| `VOLLEDIG` | elke regel had meteen een termijn |
| `TE_BEPALEN` | er vertrok een offerte met minstens één rode regel |
| `AANGEVULD` | die termijnen zijn ingevuld en de offerte is opnieuw vertrokken |

Bij `TE_BEPALEN` leest de klant in het portaal dat wij bekijken wanneer we kunnen leveren en
de offerte opnieuw sturen. Vullen wij de weken in en versturen we opnieuw, dan springt de
stand op `AANGEVULD` en ziet hij bovenaan **Levertermijn toegevoegd**. De mail zegt het dan
ook in het onderwerp — *Levertermijn ingevuld - offerte ENR-2026-0006* — met een groen blok
bovenaan en de termijn per artikel eronder. Anders lijkt het een dubbele mail en leest
niemand waarom ze er is.

Die stand komt van de server en wordt niet uit de regels afgeleid: zodra wij zelf een
termijn nalieten klopte dat afleiden niet meer.

Kiest de klant in het portaal zelf een artikel zonder voorraad, dan krijgt hij de melding
dat wij dat eerst moeten aanvaarden en de levertermijn zullen laten weten.

## Vracht kan een open post zijn

Net als de levertermijn. Bij een bestemming buiten de gewone tarieven, een order die net over
een pallet gaat of een klant die zelf laat ophalen weet je het bedrag bij het opmaken nog
niet. Zet dan **Vracht wordt later bepaald** aan (onder *Vracht aanpassen* bij de prijsopbouw).

De klant leest dan "nog te bepalen" in plaats van een bedrag, plus de mededeling dat wij het
laten weten. Er telt niets mee in het totaal — een bedrag verzinnen en later corrigeren is
erger dan een open post, want de klant rekent op wat er stond.

De order onthoudt de stand in `freight`, met dezelfde drie standen als de levertermijnen:

| Stand | Betekenis |
| --- | --- |
| `BEREKEND` | het landtarief geldt |
| `TE_BEPALEN` | de offerte vertrok met de vracht als open post |
| `AANGEVULD` | het bedrag is ingevuld en de offerte is opnieuw vertrokken |

Daarnaast kan je een **eigen vrachtbedrag** invullen dat vóór het landtarief gaat. Leeg laten
betekent: reken het tarief per pallet.

## Meldingen: wie is aan zet?

Rechtsboven staat een belletje. Het cijfer telt **alleen wat wij moeten doen** — een
levertermijn invullen, een vrachtbedrag bepalen, een voorstel beoordelen. Dat een klant zijn
offerte geopend heeft is nuttig om te weten maar geen taak, en zou het cijfer laten oplopen
tot het niets meer betekent. Die meldingen staan wel in de lijst, onder *Van de klant*.

Dezelfde vraag staat ook in de **verkooporderlijst**: naast de status staat een vlaggetje met
wat er van ons verwacht wordt (*Levertermijn invullen*, *Vracht invullen*, *Voorstel
beoordelen*, *Heropenen of laten*, *Nog niet verstuurd*). Dat "Verzonden" er staat zegt
namelijk niet of je erop moet wachten of ermee aan de slag moet.

Hetzelfde cijfer staat als **bolletje op de tab Verkoop**, en de openstaande punten staan
als lijst **bovenaan Verkoop** — niet weggestopt onder *Meer*, want dit is de lijst die je
bijhoudt, niet iets wat je gaat opzoeken. Alle drie lezen ze uit dezelfde `WorkQueue`; zonder
gedeelde bron staat er een 2 op de tab terwijl de lijst er drie toont, en gelooft niemand het
cijfer nog.

Een melding verdwijnt zodra je de zaak afhandelt. Je kan ze ook **wegklikken** (het kruisje);
dat blijft lokaal in jouw browser en komt terug zodra de melding iets anders te zeggen heeft
— "2× geopend" en "5× geopend" zijn niet hetzelfde bericht.

De meldingen worden **berekend uit de orders**, niet in een aparte tabel bijgehouden: er is
niets om bij te houden dat niet al in de orderstatus staat, en een tweede plaats waar
dezelfde waarheid staat gaat vroeg of laat uit elkaar lopen.

## Voorraad in beeld

De productkiezer toont bij elk artikel een gekleurde stip: rood is leeg, oranje krap
(minder dan tien dozen), groen ruim. Er staat altijd tekst naast, want kleur alleen is geen
informatie. Kies je meer dan er ligt, dan verschijnt een waarschuwing met wat er tekort is.

## Inkoopcalculatie: kopiëren en op papier

*Kopiëren* maakt een nieuw concept met dezelfde koersen, kosten en regels — bedoeld om snel
een variant door te rekenen: een andere containermaat, een leverancier die zijn prijs
aanpast. De status gaat altijd terug naar concept; anders zou een kopie van een ontvangen
order de voorraad een tweede keer bijboeken.

*PDF* geeft twee bladen, en welke je krijgt hangt af van de dubbelklikschakelaar:

| Stand | Wat er op het blad staat |
| --- | --- |
| Inkoopcijfers **zichtbaar** (rood thema) | alles, inclusief *Gewenste extra opbrengst* als eigen regel, met de stempel **Intern** |
| Inkoopcijfers **verborgen** (groen thema) | diezelfde calculatie zonder die regel; het bedrag staat als *Toeslagen en afronding* |

In beide gevallen is het **totaal hetzelfde** en klopt de kostprijs per stuk met wat wij
hanteren. Een klant die meekijkt ziet dus waar wij op uitkomen, niet hoeveel marge erin zit.

De neutrale post is er met opzet: zonder die regel telt de kolom niet op tot het totaal, en
dat valt op. Liever een sluitpost dan een klant die zelf het verschil uitrekent. **Volledig
verbergen kan niet** — wie de kolom natelt houdt een verschil over. Wil je dat het echt
onzichtbaar is, verwerk de opbrengst dan in de kostprijs per stuk in plaats van als aparte
post.

## Talen: de klant bepaalt, niet wij

Elke klant heeft een **taal** (verplicht veld bij het aanmaken). Daarin vertrekken zijn
offerte-PDF, zijn mail en zijn klantportaal. Intern blijft alles Nederlands — het is een
verkoopsysteem voor ons, geen tweetalig product.

Acht talen: **Nederlands, Frans, Engels, Duits, Spaans, Pools, Portugees, Turks.**

De teksten staan in één bestand, `DocumentText.java`, als map per taal. Bewust geen
`.properties`-bundels: het gaat om ruim honderd woorden die je in één scherm wil overzien,
en `DocumentTextTest` bewaakt dat **elke taal exact dezelfde sleutels** heeft. Een
ontbrekende sleutel geeft namelijk geen fout maar een leeg vak op een offerte die al bij de
klant ligt.

Bij het **downloaden** van een offerte-PDF krijg je een keuzelijst met de taal van de klant
al geselecteerd. **Versturen** gebruikt altijd zijn taal, ongeacht wat je daar kiest — de
keuzelijst is er om even een Engelse versie mee te geven aan iemand die de offerte intern
moet doorgeven, zonder daarvoor de klantfiche te wijzigen.

De klant kan in zijn portaal rechtsboven zelf een andere taal kiezen (het bolletje naast het
logo). Die keuze blijft in zijn browser bewaard en verandert **niets** aan zijn fiche: de
volgende offerte vertrekt gewoon weer in de taal die wij afgesproken hebben. Wie in Frankrijk
zit maar liever Engels leest hoeft daarvoor niet te bellen.

Het klantportaal krijgt zijn teksten **van de server** mee met de offerte, niet uit een
eigen Angular-bundel. Zo staan de PDF, de mail en het scherm gegarandeerd in dezelfde
woorden en hoeft een nieuwe taal maar op één plaats toegevoegd te worden.

Ook vertaald: de **wettelijke BTW-vermelding**. Een Franse klant die "Vrijstelling van BTW"
leest weet niet of hij BTW moet betalen, en dat is precies wat die zin moet duidelijk maken.
De wetsartikelen blijven staan zoals ze heten — `Art. 39bis W.BTW` is een verwijzing, geen
vertaalbare tekst.

### Het lettertype doet ertoe

De PDF sluit **DejaVu Sans** in (`backend/src/main/resources/fonts/`). Zonder ingesloten
lettertype valt de PDF-bibliotheek terug op de ingebouwde fonts, en die kennen alleen
West-Europees schrift: een Poolse offerte toont dan `P#atno##` in plaats van `Płatność` en
een Turkse `numaras#` in plaats van `numarası`. Dat merk je niet bij het bouwen — alleen de
klant ziet het, op het document dat hij moet tekenen.

DejaVu is vrij te gebruiken en te herdistribueren, ook commercieel (zie
`fonts/LICENSE.txt`). Vandaar dit lettertype en niet een systeemfont van de ontwikkelmachine:
die zijn zelden vrij mee te leveren.

### Datums per taal

| Taal | Vorm |
| --- | --- |
| nl, fr, de, es, pt, tr | `25/05/2026` |
| pl | `25.05.2026` |
| en | `25 May 2026` |

Engels krijgt de maand voluit omdat `05/25` en `25/05` aan weerszijden van de oceaan anders
gelezen worden, en bij een levertermijn wil je daar geen twijfel over.

## Producten vertalen via CSV

Alleen **naam, beschrijving en kleur** zijn vertaalbaar. De rest van een product —
afmetingen, barcodes, HS-code, doosinhoud — is universeel en blijft Engels: die vertalen
levert niets op en verdubbelt de kans op tegenstrijdigheden.

Vertalen gebeurt in een spreadsheet, vaak door iemand buiten het bedrijf. Vandaar een
bestand eruit en hetzelfde bestand er weer in:

```bash
curl -u enrosedadmin:WACHTWOORD http://localhost:8080/api/products/translations/csv -o vertalingen.csv
```

Terugladen kan via hetzelfde adres met een POST (multipart, veld `file`). Het antwoord zegt
hoeveel producten en rijen bijgewerkt zijn en **wat er misging** — een onbekende SKU of
taalcode wordt gemeld en overgeslagen, niet stil genegeerd. Anders levert een typfout een
import op die "gelukt" zegt terwijl er niets veranderd is.

Twee dingen die het bestand robuust maken in Excel: een **UTF-8 BOM** (anders maakt Excel van
"Rosé" iets anders) en **puntkomma** als scheidingsteken. Een beschrijving met een puntkomma
erin wordt netjes tussen aanhalingstekens gezet.

De export vult elke taal met de **basiswaarden** als vertrekpunt — een vertaler die een leeg
bestand krijgt weet niet waar te beginnen. Wat gelijk blijft aan de basis telt bij het
inlezen niet als vertaling; anders staat na één import in elke taal de Nederlandse naam en
lijkt alles vertaald terwijl er niets vertaald is.

Wat niet vertaald is valt terug op de basiswaarde. Liever de Nederlandse naam op een Franse
offerte dan een leeg vak.

## Datums in de invoervelden

Onze eigen schermen tonen `25/05/2026`. (Wat de **klant** ziet volgt zijn taal — zie de
tabel hierboven.)

Dat vraagt een eigen veld. Een `<input type="date">` toont de datum namelijk in de taal van
de **browser**, niet in die van de pagina: op een toestel dat op Engels staat lees je
`05/25/2026`, en dat is precies de verwarring die je op een offerte niet wil. Het veld hier
is een tekstvak dat altijd `dd/mm/jjjj` toont, met de kalenderknop ernaast voor wie liever
klikt. Intern blijft alles gewoon ISO.

Bij het intikken mag je ruim zijn: `25/05/2026`, `1-3-2027`, `25.5.26` en `25052026` komen
allemaal aan. Wat er niet uit te lezen valt wordt geweigerd en het veld springt terug naar
de vorige datum — een onleesbare datum stil wegschrijven is erger dan hem weigeren.

Weken worden uitgeschreven: `2026-W42` wordt *week 42 (12/10 - 18/10/2026)*. Een klant hoort
niet zelf te moeten opzoeken wanneer week 42 valt. De weeknummering rond nieuwjaar ligt vast
in `DocumentFormatTest`: week 1 van 2026 begint op 29/12/2025 en 2026 heeft een week 53 die
tot 03/01/2027 loopt.

## Ordernummers

Verkoop- en inkoopordernummers worden automatisch gegeven maar zijn **aanpasbaar**: bij een
overstap uit een ander systeem loopt de nummering door, en soms hoort een offerte bij een
bestaand dossier. Twee orders met hetzelfde nummer wordt geweigerd — elke verwijzing ernaar
zou dan dubbelzinnig zijn, in een mail, op een factuur of in de boekhouding.

## Onze bedrijfsgegevens

*Instellingen → Onze bedrijfsgegevens*: naam, adres, BTW-nummer, IBAN, voettekst. Ze staan
in de database en niet in de configuratie — een adreswijziging hoort geen herstart van de
server te vragen — en verschijnen op elke offerte, factuur en catalogus.

## Foto's

Onbeperkt in aantal, in volle kwaliteit en downloadbaar. Eén bestand mag maximaal 25 MB
zijn en moet werkelijk JPEG, PNG, GIF of WebP zijn. De bytes gaan daarna ongewijzigd de
database in en komen er ongewijzigd weer uit — geen herschaling, geen hercompressie — zodat
een foto die de leverancier op 4000 px aanlevert bruikbaar blijft voor drukwerk of een
webshop. De eerste foto is de hoofdfoto en verschijnt in lijsten en op orderregels.

Alles in de database houden betekent één back-up en één plek om te beveiligen. De keerzijde
is dat de database hard groeit en dat blobs niet door een CDN gecachet worden. Loopt dat
op, dan schrijf je een S3-variant naast `DatabasePhotoStorage`: de poort `PhotoStorage`
verandert niet.

## Product: drie dingen die niet door elkaar mogen

| Veld | Wat |
|---|---|
| `dimensions` | het artikel zelf — 15 × 30 × 12 cm |
| `colour` | de kleur — "Rood", "Roze" |
| `carton` | de omdoos waarin het verscheept wordt |

De kleur staat apart en niet in de productnaam verwerkt: zodra er een tweede productoptie
bijkomt (maat, afwerking) hoef je bestaande gegevens niet uit elkaar te pluizen.

**Kopiëren naar een andere kleur**: op een product staat *Kopiëren naar een andere kleur*.
Maten, prijzen en verpakking gaan mee; foto's en barcodes niet — die verschillen per kleur,
en twee artikelen met dezelfde EAN geeft in het magazijn van je klant een probleem dat
niemand meteen ziet.

De categorie komt uit een **vaste lijst** (Preserved, Glas, Acryl, Heart box), te beheren
bij Instellingen. Vrij tekstveld werd te snel een verzameling spelfouten.

## Rekenregels

Alles rekent op de server, in `BigDecimal`. De frontend toont alleen — er staat geen tweede
rekenmotor die uit de pas kan lopen met de eerste.

### Landed cost

```
1. GOEDEREN      aantal × (EXW + extra kost per stuk), in USD of RMB
2. ORIGIN        fabriek → Chinese haven: voorvervoer, exportdocumenten, THC, verzekering
3. ZEEVRACHT     laadhaven → loshaven
   ─────────────────────────────────────────────────────────── EU-grens
   douanewaarde = 1 + 2 + 3
4. INVOERRECHT   douanewaarde × percentage van de HS-code van dát product
5. DESTINATION   loshaven → magazijn: THC, inklaring, wegtransport  (niet belast)
6. EXTRA         gewenste opbrengst
```

Origin en destination staan bewust apart: alles vóór de grens wordt mee belast, alles erna
niet. `LandedCostCalculatorTest` legt dat vast tegen de Excel — **€ 44.749,38 totaal en
€ 22,7385 per set** — plus twee tests die bewijzen dat origin wél en destination níét in
de douanewaarde telt. Wijkt die test af, dan is de motor stuk, niet de test.

### Verkoop

Dozen per pallet worden **echt gestapeld** gerekend: dozen per laag op 120 × 80 cm (beide
oriëntaties, de beste wint) × lagen tot de maximale hoogte of het maximale gewicht.

```
vracht = max(pallets × tarief per pallet, minimum) + administratie
```

Opslag komt van het product, of — als de order dat zo instelt — als één percentage over de
hele order. Daarbovenop kan een **losse extra korting** (bijvoorbeeld een beurskorting) met
een eigen omschrijving; die rekent over wat er ná de staffelkorting nog staat, zodat de twee
niet dubbel over hetzelfde bedrag lopen, en verschijnt met die naam op de offerte en in het
klantportaal. **Opslag is niet hetzelfde als marge**: opslag rekent vanaf de kostprijs, marge
vanaf de verkoopprijs. 45 % opslag = 31 % marge, en staffelkorting drukt dat verder.

## Marktdata voor containervracht

Het dashboard houdt twee soorten cijfers bewust uit elkaar:

- een **eigen forwarderofferte** is een echte USD-prijs per 40ft-container en kan per
  vertrekhaven worden genoteerd;
- een **marktindex** bestaat uit punten en laat alleen de marktrichting zien. Indexpunten
  zijn geen USD-vrachttarief en worden nooit naar een vermeende routeprijs omgerekend.

Shanghai → Rotterdam blijft apart als de USD-benchmark van de Drewry World Container
Index. Voor Ningbo gebruikt de app uitsluitend de exacte NCFI-route *Ningbo → Europe*;
de Baltic-publicatie beschrijft die route als Ningbo-Zhoushan naar Hamburg en Rotterdam.
Voor Nansha/Guangzhou en Yantian/Shenzhen is geen exacte Europa-reeks geïmplementeerd:
de officiële CCFI China → Europa is daar alleen een **brede referentie** over tien Chinese
vertrekhavens. De eigen forwarderofferte blijft dus het prijsanker. De officiële
Guangzhou/GBA-exportindex die bij het brononderzoek werd gevonden bestrijkt ASEAN-routes,
niet Europa, en wordt daarom niet als vervanger gebruikt.

Bronnen en voorwaarden:

- [Drewry — free market insights](https://www.drewry.co.uk/free-market-insights),
  [licentievoorwaarden](https://www.drewry.co.uk/maritime-research/maritime-research-related-content/standard-licence-terms)
  en [Container Freight Rate Insight/API](https://www.drewry.co.uk/maritime-research-products/container-freight-rate-insight-annual-subscription?redirected=1);
- [Baltic Exchange — NCFI weekpublicaties](https://www.balticexchange.com/en/data-services/WeeklyRoundup/ningbo/news/2026/ningbo-containerised-freight-index-070826.html)
  en [data policy](https://www.balticexchange.com/en/site-services/data-policy.html);
- [Shanghai Shipping Exchange — CCFI-definitie](https://en.sse.net.cn/indices/intro_ccfitt.htm)
  en [User Agreement](https://en.sse.net.cn/indices/agreetext.htm);
- [Guangzhou Port Authority — scope GBA-exportindex](https://gwj.gz.gov.cn/xwzx/gzgxw/content/post_9252150.html).

ENROSED heeft bevestigd dat deze interne installatie de benodigde provider-toestemming
heeft. Daarom zijn alle drie bronconnectors standaard **aan**. Een bron kan operationeel
altijd expliciet worden uitgezet door de bijbehorende vlag op `false` te zetten:

```properties
DREWRY_AUTOMATED_ACCESS_AUTHORIZED=false
NCFI_AUTOMATED_ACCESS_AUTHORIZED=false
CCFI_AUTOMATED_ACCESS_AUTHORIZED=false
```

Een achtergrondtaak controleert standaard dagelijks om 03:15 UTC; met
`MARKET_DATA_REFRESH_CRON` kan dat tijdstip worden gewijzigd en `off` schakelt de taak uit.
De dashboard-API probeert dezelfde begrensde controle als fallback. Een databaseclaim
zorgt dat meerdere app-nodes dezelfde bron niet dubbel opvragen. Providerpublicaties worden op datum gededupliceerd;
een bron zonder publicatiedatum krijgt hoogstens één lokale observatie per zeven dagen.
NCFI vult daarnaast met maximaal zes archiefpagina's per dag geleidelijk circa zes
maanden aan exacte Ningbo-Europa-punten; na voldoende historie stopt die aanvulling.
Bij een netwerk- of parsefout blijft de laatst geldige cache staan, samen met bron,
publicatiedatum, laatste controlemoment en foutstatus. Ook wanneer een connector
operationeel uitstaat, werken handmatig ingevoerde forwarderoffertes volledig door.

Provider-toestemming en technische servertoegang zijn twee afzonderlijke zaken. Wanneer
Baltic een `Challenge Validation`-pagina teruggeeft, probeert de connector die beveiliging
niet te omzeilen. De bronstatus wordt dan `PROVIDER_ACCESS_REQUIRED`; configureer via de
provider de geautoriseerde feed of credentials, of laat het server-IP allowlisten. Zodra
die toegang index-HTML teruggeeft, werken dezelfde parser, dagelijkse controle en begrensde
historie-aanvulling zonder verdere datamigratie.

## Structuur

```
src/app/
  core/api/        modellen, HTTP-diensten, auth, interceptor, guard
  shared/          pipes, bottom sheet, toasts, fotobeheer, paginakop
  features/        login, dashboard, sales, portal, customers,
                   purchasing, suppliers, products, settings, more
backend/           Quarkus — zie backend/README.md
```

## Nog in te vullen met echte cijfers

Staat ook in `DemoDataLoader`:

1. EXW-prijzen zijn afgeleid van de oude EUR-lijst, geen leveranciersquote.
2. Kartongewichten zijn schattingen — ze bepalen mee hoeveel dozen op een pallet gaan.
3. Palletvracht en minimum orderwaardes per land zijn richttarieven.
6. **Productafmetingen**: op het containeroverzicht staan er twee ("11*11cm"), dus de derde
   is een aanname — bij ronde en vierkante artikelen is lengte gelijk aan breedte genomen.
   Nameten voor de echte catalogus.
4. **Invoerrechtpercentages horen nagekeken in de TARIC-databank van de EU.** Wat er staat
   is configuratie, geen douaneadvies.
5. SMTP-instellingen in het `prod`-profiel zijn placeholders.

## Bekende beperkingen

- Prijzen worden live herrekend. Zodra een offerte verstuurd is, horen de gebruikte
  prijzen, kortingen en vrachttarieven als momentopname op de orderregel te staan.
- Lokaal draait H2 als bestand in `backend/data`, met `update` als schemastrategie: de
  gegevens blijven staan tussen herstarts. Wil je een schone start met verse startdata,
  verwijder dan die map. Productie staat op PostgreSQL met `validate`.
- `_legacy-html-mock/` bevat de allereerste HTML-mock. Mag weg.
- In het **klantportaal** is *Aanvaarden en tekenen* groen en *Offerte afwijzen* een rustige
  knop zonder rood. Een openstaande levertermijn is er oranje in plaats van rood. Rood naast
  een handtekening leest als een waarschuwing en schrikt af; op onze eigen schermen blijft
  rood wél rood, want daar is het een signaal en geen uitnodiging.
- De vertalingen zijn met zorg gemaakt maar niet door een moedertaalspreker nagelezen. Voor
  de vier talen die je het meest zal gebruiken is dat een half uur werk dat de moeite loont —
  zeker de zinnen die op de offerte zelf staan.
- Betaalvoorwaarden en de notitie op een offerte zijn vrije tekst uit de klantfiche. Die
  gaan **niet** door de vertaling: staat er "30 dagen", dan leest een Poolse klant "30 dagen".
  Vul ze in de taal van de klant in.
- Enum-kolommen krijgen bewust géén CHECK-constraint in de database (zie `columnDefinition`
  op `language`). Hibernate zet daar anders de waarden in die op dat moment bestaan, en
  `update` als schemastrategie verbreedt zo'n constraint niet — een negende taal toevoegen
  laat de database de rij dan weigeren.
