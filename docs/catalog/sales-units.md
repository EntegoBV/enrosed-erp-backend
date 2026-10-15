# Stukprijs en displayprijs

Bij een display bepaalt `packaging.salesUnit` wat één opgeslagen orderaantal en
de bijbehorende prijs betekenen: `PIECE` voor één los artikel, `DISPLAY` voor
het volledige display. De inhoud staat apart in `packaging.piecesPerUnit`.
In het ERP heet de keuze **Prijs geldt per** (Eén stuk of Display van 12), op de
kaart "Hoe verkoop je dit product?" bij Verkoopprijs.

- Bowl M: €3,95 per stuk; display van 8: €31,60.
- Bowl XL: €7,95 per stuk; display van 6: €47,70.
- Lange steelrozen in transparante box: €49,95 per display van 12;
  circa €4,16 per roos. De omdoos bevat 4 displays, dus 48 rozen.

De catalogus presenteert de stukprijs met daaronder de displayprijs en de
inhoud van het display ("Display (12 st.): € 35,40"; tot 2026-10-15 "Set").
Een opgeslagen "Set"-standaardtekst gaat bij het opstarten mee naar "Display";
een eigen tekst uit het dashboard blijft staan. Een afgeronde terugrekening
krijgt een benaderingsteken.
Offertes en facturen, het portaal en de website tonen eerst de displayprijs; het
ERP toont eerst de opgeslagen prijs met de displayprijs eronder. Het woord onder
een prijs zegt altijd waarvoor die prijs geldt ("per diamant", "per display").
Een displayprijs naast een prijs vóór korting heeft dezelfde kortingsbasis.
Het ERP zegt overal "display", ook in inkoop, nacalculatie en inkoop-PDF's,
nooit "set".

De omzetting naar een zichtbare tweede prijs wijzigt geen bedragen, btw,
orderregels, voorraden of omdoosaantallen. Opgeslagen PDF-bestanden blijven
ongewijzigd; nieuw gegenereerde documenten gebruiken de nieuwe uitleg.
Een wijziging van de ERP-verkoopeenheid rekent bestaande waarden niet automatisch
om. Controleer daarom de prijs, voorraad- en omdooseenheid bij zo'n wijziging.

De introductiemigratie maakt de bestaande displaybasis expliciet voor de twee
families steelrozen met 12 rozen per display en 4 displays per omdoos. Varianten
zonder geconfigureerde displayverpakking worden overgeslagen. De bestaande
inhoud van de witte diamantroos (9) blijft behouden.

## Klant bestelt per

Naast de prijsbasis kiest Enrosed per product **per hoeveel** de klant bestelt
(`packaging.orderStep`; tot 2026-10-15 heette dit "Verkocht per"). Eén keuze
geldt voor offertes en facturen, het klantportaal, de offerteaanvraag en
bestellingen op de website.

- **Per stuk** (`UNIT`): één stuk in de eigen eenheid van het product (per stolp,
  per hart, per box), of één display bij een displaybasis. Er wordt niet afgerond.
- **Per display** (`PACKAGE`): een display dat per stuk geprijsd is, rondt af op
  zijn inhoud. 10 bowls bij een display van 8 worden er 16. Een geschenkverpakking
  bevat één stuk en rondt dus niet af; een product met displaybasis telt al
  displays. Kan alleen met een display of geschenkverpakking.
- **Volle omdoos** (`CARTON`, de standaard voor elk bestaand product): het aantal
  rondt af op de inhoud van één omdoos. 13 stolpen bij 12 per doos worden er 24.

De opgeslagen hoeveelheid blijft in de verkoopeenheid; er wordt niets omgerekend
en de prijs per stuk blijft dezelfde, zonder toeslag voor een aangebroken doos.
Een aangebroken doos telt voor volume, gewicht, pallets en handling als één volle
doos. Partnerafspraken, offertes aan containerkost, creditnota's en gesplitste
leveringen ronden nooit af. Elke verkoopregel onthoudt het veelvoud waarop hij
laatst afgerond werd, zodat een latere wijziging van het product een verstuurde
of betaalde offerte of factuur niet verandert. In het Excel- en CSV-bestand is
het de laatste kolom `verkocht_per`: `omdoos`, `verpakking` of `stuk`.
