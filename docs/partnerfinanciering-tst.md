# Partnerfinanciering en ontvangen betalingen — TST

## Bedrijfsafspraak

De partner stort aan ENROSED. ENROSED betaalt de externe containerkosten. Na de veiling wordt de netto veilingopbrengst, na veilingkosten, verrekend met de externe containerkosten en het afgesproken aandeel voor ENROSED. Het percentage financiering vooraf staat los van het aandeel in het resultaat.

| Scenario | Vooraf financieren | Verkoopdocumenten | Resultaat |
| --- | --- | --- | --- |
| Reguliere verkoop | Normale klantafspraak | Offerte / verkoopfactuur | Normale omzet en kostprijs |
| Samen inkopen | Bijvoorbeeld partner 50%, ENROSED 50% | Partnervoorschotofferte, voorschotfactuur, slotfactuur | Afgesproken aandeel in het veilingresultaat |
| Partner financiert volledig | Partner 100% | Dezelfde partnerdocumenten | Bijvoorbeeld 50/50 verdeling van het veilingresultaat |
| ENROSED financiert volledig | Partner 0% vooraf | Slotfactuur na de veiling | Externe kost terug plus afgesproken resultaat |

Een partnerklant kan daarnaast gewone producten kopen: het scenario wordt per document gekozen. Partnerdocumenten zijn vrijgesteld van het commerciële minimumorderbedrag en de automatische landenafhandeling. Interne ENROSED-opslag zit niet nog eens in de externe kostbasis voor winstdeling.

## Werkwijze

1. Open de inkooporder en kies **Partnercontainer**. Selecteer de partner, zijn financieringspercentage en het aandeel van ENROSED in het veilingresultaat.
2. Maak de voorschotofferte vanuit de container, of koppel een bestaand conceptdocument. Het document bewaart de inkoopkoppeling. Kies volledige betaling of **1/3 bij start productie / 2/3 na productie**.
3. Maak de factuur van de offerte. Geef haar uit zonder e-mail als de partner buiten het systeem al akkoord is; uitgifte zet de inhoud vast. Verzending kan daarna afzonderlijk worden vastgelegd.
4. Registreer elke ontvangst met bedrag, ontvangstdatum, exact tijdstip, tijdzone en bankreferentie. De factuur toont de afzonderlijke ontvangsten, de betaaltermijnen en het resterende bedrag. De gekoppelde container toont dezelfde ontvangsten.
5. Na de veiling: vul per product het volledige bruikbare aantal en de netto veilingopbrengst in. Controleer de externe kosten; de afrekening vermeldt of die nog voorlopig zijn. Maak de slotfactuur. Reeds uitgereikte voorschotten worden verrekend, ook als nog niet alles ontvangen is.
6. Ontvangsten op de slotfactuur worden op dezelfde manier genoteerd. Een negatief saldo blijft zichtbaar als credit en wordt niet als inkomende betaling behandeld.

De oorspronkelijke registratietijd en medewerker blijven bewaard wanneer een ontvangst wordt gecorrigeerd. Verwijderen trekt de ontvangst in met een logboekregel; het historische record blijft bestaan. Facturen met betaalhistoriek en containers met gekoppelde documenten moeten worden gearchiveerd om de samenhang te bewaren.

## Rekenvoorbeeld zonder btw

Externe kost € 12.000, netto veilingopbrengst € 16.000, resultaat € 4.000, aandeel ENROSED 50% = € 2.000.

| Partner financiert | Voorschotfactuur | Productiestart | Na productie | Slotfactuur | Totaal aan ENROSED | Resultaat ENROSED |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 100% | € 12.000 | € 4.000 | € 8.000 | € 2.000 | € 14.000 | € 2.000 |
| 50% | € 6.000 | € 2.000 | € 4.000 | € 8.000 | € 14.000 | € 2.000 |
| 0% | € 0 | — | — | € 14.000 | € 14.000 | € 2.000 |

Als in het tweede scenario slechts € 2.000 is ontvangen, blijven € 4.000 op de voorschotfactuur en € 8.000 op de slotfactuur open. De slotfactuur verrekent de gefactureerde € 6.000; betaalachterstand wordt niet opnieuw gefactureerd.

Bij netto opbrengst € 10.000 is het resultaat € -2.000 en bij dezelfde verdeling het aandeel ENROSED € -1.000. Een vooraf volledig gefinancierde container geeft dan een slotcredit van € 1.000. De invoer benoemt expliciet dat de resultaatsverdeling ook verliezen omvat. Afwijkende afspraken over verliesverdeling vergen een afzonderlijke uitbreiding.

Ontvangsten en openstaande facturen gebruiken bedragen inclusief eventuele btw. Het gerealiseerde resultaat gebruikt bedragen exclusief btw. Eigen kasinleg is het positieve verschil tussen geregistreerde externe uitgaven en partnerontvangsten; dit is een cashindicator, geen winstberekening.

## Inzicht in het ERP

- **Verkoop:** onderscheid tussen reguliere verkoop, partnervoorschotten en slotfacturen; open bedrag op basis van werkelijke ontvangsten.
- **Inkoop:** gekoppelde offertes/facturen, afgesproken financiering, uitgereikte voorschotten, ontvangen voorschotten, open bedragen, slotafrekening en eigen kasinleg per container.
- **Homepage:** ontvangen deze maand, openstaande betalingen, gerealiseerd partnerresultaat en eigen kasinleg. De tegels openen het relevante overzicht.
- **Analyses:** partnercontainers apart van gewone verkoop; voorschotten tellen niet als omzet of winst. De slotafrekening legt externe kost en gerealiseerd resultaat vast.
- **Kosten & bank:** ontvangstenregister met datum/tijd en doorklik naar factuur/container. Een banksaldo kan een exact peiltijdstip krijgen, zodat eerdere ontvangsten niet opnieuw worden opgeteld.

## Grenzen van deze versie

- Twee betaalplannen: volledig, of 1/3 + 2/3. Vrije termijnen en afzonderlijke vervaldata per productiemijlpaal zijn nog geen invoerbare planning.
- De slotfactuur rekent één volledige bruikbare container af. Meerdere gedeeltelijke veilingen/afrekeningen per container zijn nog geen afzonderlijk proces.
- Credits en te veel ontvangen bedragen zijn zichtbaar; daadwerkelijke terugbetalingen en automatische verrekening met andere facturen hebben nog geen eigen betaalregister.
- Ontvangsten worden handmatig ingevoerd; er is geen bankimport of automatische matching van afschriften.
- De bankdoorrekening is een totaalindicatie. Ontvangsten zijn nog niet aan afzonderlijke bankrekeningen toegewezen. Voor meerdere rekeningen met verschillende peildata is bankafstemming per rekening nodig.

## Uitrol

Deze wijziging wordt uitsluitend op de `test`-branches gepubliceerd. Frontend: `https://enrosed-erp-frontend-test.vercel.app`; API: `https://enrosed-erp-backend-test.up.railway.app`. De PostgreSQL-migraties draaien vóór de TST-applicatie start; historische betaalmarkeringen worden eenmalig als herkenbare ontvangsten overgenomen. TST heeft e-mailmocking aan en pushberichten uit.
