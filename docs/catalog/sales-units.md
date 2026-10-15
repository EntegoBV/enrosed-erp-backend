# Stukprijs en displayprijs

Bij een display bepaalt `packaging.salesUnit` wat één opgeslagen orderaantal en
de bijbehorende prijs betekenen: `PIECE` voor één los artikel, `DISPLAY` voor
het volledige display. De inhoud staat apart in `packaging.piecesPerUnit`.
De keuze is bewerkbaar bij de verpakking in het ERP.

- Bowl M: €3,95 per stuk; display van 8: €31,60.
- Bowl XL: €7,95 per stuk; display van 6: €47,70.
- Lange steelrozen in transparante box: €49,95 per display van 12;
  circa €4,16 per roos. De omdoos bevat 4 displays, dus 48 rozen.

De catalogus presenteert de stukprijs met daaronder de setprijs en setinhoud.
Een afgeronde terugrekening krijgt een benaderingsteken. Offertes en facturen
houden de opgeslagen commerciële hoeveelheid en eenheidsprijs als hoofdwaarde;
de expliciete eenheid en kleinere omrekening voorkomen verwarring.
Een setprijs naast een prijs vóór korting heeft dezelfde kortingsbasis.

De omzetting naar een zichtbare tweede prijs wijzigt geen bedragen, btw,
orderregels, voorraden of omdoosaantallen. Opgeslagen PDF-bestanden blijven
ongewijzigd; nieuw gegenereerde documenten gebruiken de nieuwe uitleg.
Een wijziging van de ERP-verkoopeenheid rekent bestaande waarden niet automatisch
om. Controleer daarom de prijs, voorraad- en omdooseenheid bij zo'n wijziging.

De introductiemigratie maakt de bestaande displaybasis expliciet voor de twee
families steelrozen met 12 rozen per display en 4 displays per omdoos. Varianten
zonder geconfigureerde displayverpakking worden overgeslagen. De bestaande
inhoud van de witte diamantroos (9) blijft behouden.

## Verkocht per

Naast de prijsbasis kiest Enrosed per product **per hoeveel** het verkocht wordt
(`packaging.orderStep`). Eén keuze geldt voor offertes en facturen, het
klantportaal, de offerteaanvraag en bestellingen op de website.

- **Volle omdoos** (`CARTON`, de standaard voor elk bestaand product): het aantal
  rondt af op de inhoud van één omdoos. 13 stolpen bij 12 per doos worden er 24.
- **Per verpakking** (`PACKAGE`): een display dat per stuk geprijsd is, rondt af op
  zijn inhoud. 10 bowls bij een display van 8 worden er 16. Een geschenkverpakking
  bevat één stuk en rondt dus niet af; een product met displaybasis telt al
  displays. Kan alleen met een display of geschenkverpakking.
- **Per stuk** (`UNIT`): één stuk in de eigen eenheid van het product (per stolp,
  per hart, per box), of één display bij een displaybasis. Er wordt niet afgerond.

De opgeslagen hoeveelheid blijft in de verkoopeenheid; er wordt niets omgerekend
en de prijs per stuk blijft dezelfde, zonder toeslag voor een aangebroken doos.
Een aangebroken doos telt voor volume, gewicht, pallets en handling als één volle
doos. Partnerafspraken, offertes aan containerkost, creditnota's en gesplitste
leveringen ronden nooit af. Elke verkoopregel onthoudt het veelvoud waarop hij
laatst afgerond werd, zodat een latere wijziging van het product een verstuurde
of betaalde offerte of factuur niet verandert. In het Excel- en CSV-bestand is
het de laatste kolom `verkocht_per`: `omdoos`, `verpakking` of `stuk`.
