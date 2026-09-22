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
