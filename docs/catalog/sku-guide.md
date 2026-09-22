# ENROSED SKU-opbouw

Sinds 22 september 2026 hebben bestaande artikelen een korte SKU: **model-formaat/verpakking-kleur**. De product-ID blijft de vaste technische identiteit; een SKU kan in het ERP onder **Basisgegevens** worden aangepast. Productlinks en barcodes veranderen daardoor niet.

## Voorbeelden

| SKU | Betekenis |
| --- | --- |
| BOWL-M-RD | Bowl M, rood |
| BOWL-XL-WH | Bowl XL, wit |
| DOM-12X25-RD | Stolp, diameter 12 cm × hoogte 25 cm, rood |
| DOM-12X25-NB-RD | Dezelfde stolpvariant zonder geschenkdoos |
| DOM-15X30-3R-RD | Stolp 15 × 30 cm met drie rozen, rood |
| STEM-SLV-RD | Steelrozen in transparante hoezen met display, rood |
| STEM-BOX-RD | Lange steelrozen in transparante boxen met display, rood |
| FBH-40-PD | Foamrozenbeer met hart, 40 cm, panda |

## Modelcodes

| Code | Model |
| --- | --- |
| DIAM-D / DIAM-XL | Diamantroos met display / XL diamant |
| BOWL-M / BOWL-XL | Bowl M / XL |
| DOM | Rozenstolp; maat is diameter × hoogte in cm |
| STEM-SLV / STEM-BOX | Steelroos in hoes / transparante box, met display |
| MIR / ACR / CUBE | Enkele roos op spiegelbasis / acrylbox / Elegance Flower Cube |
| HRT16 / FRAME16 | Hartvormige spiegeldoos met 16 rozen / vierkant frame met rozenhart |
| BOX9 / BOX16 | Flowerbox met 9 / 16 rozen |
| SOAP-WIN / SOAP-LED / SOAP-BOX | Zeeproos in vensterdoos / met LED / in box |
| FHH-25 / FHH-40 | Half hart van foamrozen, 25 / 40 cm |
| FH-15 | Hart van foamrozen, 15 cm |
| FB-25 | Foamrozenbeer 25 cm |
| FBH-25 / FBH-40 | Foamrozenbeer met hart, 25 / 40 cm |
| ROSE-WIN | Roos in vensterdoos; huidig artikel blijft demo |
| DEMO | Intern demoartikel; blijft uitgesloten van publieke kanalen |

## Kleurcodes

| Code | Kleur |
| --- | --- |
| RD | Rood |
| PK | Roze |
| CP | Kersenroze |
| NV | Marineblauw |
| BL | Blauw |
| LB | Lichtblauw |
| WH | Wit |
| MX | Gemengd |
| PD | Panda |
| FU | Fuchsia |
| CH | Champagne |
| LI | Lila |

De codes zijn in iedere taal hetzelfde. Een bestaande kleur wordt niet op basis van de SKU hernoemd. Bij beren en foamharten volgt de SKU het bestaande handelsformaat; fysieke buitenmaten blijven afzonderlijke ERP-velden.

Alle 76 productieartikelen met oude en nieuwe SKU staan in [het omzetoverzicht](sku-overview-2026-09-22.csv). TST bevat een oudere, gemengde 25cm-beer onder product 116: die krijgt daar FB-25-MX. In productie is product 116 de rode 40cm-beer met hart (FBH-40-RD). De migratie controleert de familie en variantidentiteit en verwisselt deze artikelen niet.

Bestaande opgeslagen PDF-bestanden blijven intact. Een opnieuw gegenereerd document gebruikt de huidige ERP-SKU.
