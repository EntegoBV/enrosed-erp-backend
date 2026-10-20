# Inventory quantities when a selling unit contains several pieces

New stock counts and closing snapshots use `quantityBasis = BASE_PIECE`. A quantity of 95 means 95 physical pieces, including pieces left in an opened display. Operational stock remains complete selling units plus loose pieces: `quantity * piecesPerUnit + loosePieces`. Booking a count divides the physical target with integer quotient/remainder, under the stock product lock.

For example, eight displays containing twelve stems become seven complete displays plus eleven loose stems after one TICA stem sale. A count sees 95 stems. A display acquisition cost of EUR 44.95 gives a stock value of EUR 355.85 for those 95 stems.

The input boundaries normalized for a new closing are:

1. Current stock levels, including loose pieces.
2. Count entry, booking check, differences and booking targets.
3. Editable legacy counts, converted once with their frozen factor and an incremented revision.
4. Booked count anchors, using the frozen count's basis and factor.
5. Ledger deltas and resulting quantities, using each new movement's factor and loose delta/after.
6. The predecessor ledger row used to establish a movement's effective delta.
7. Current purchase-lot capacity, ordered/received/damaged quantities and allocation divisors.
8. Per-unit costs divided by the lot's selling-unit factor, retained at twelve decimal places.
9. Carried FINAL layers, copied and converted using their owning closing's frozen article metadata.
10. Prior-year lot cost comparisons, on the same physical basis.
11. Issued invoice quantities, including older informational invoice rows.
12. Partner shipments and partner quantity decisions.
13. Goods in transit, retaining original total monetary values.
14. Third-party and write-down decisions; legacy concept decisions use their original snapshot basis.
15. Opening layers, with an explicit basis on new input. An unconsumed legacy display opening without a frozen factor blocks finalization and asks for documented replacement.

Correction-version comparisons normalize detached copies when the versions have different bases. Stored BOOKED counts and FINAL closings are never converted in place. Existing FINAL PDF/XLSX files remain their stored originals. Product metadata and closed-layer quantities are not rewritten to make a new year's calculations work.

New per-piece cost fields use `numeric(27,12)`; total monetary values retain cent precision. Twelve decimal places prevent a repeated division by twelve from losing cents in carried stock. Existing selling-unit cost calculation remains unchanged upstream.

A TICA sale's original date is shown separately from its immutable ledger booking timestamp. Imports spanning a year boundary enter the existing movement-review flow rather than silently backdating the stock book.

Regression coverage includes 95-piece count/posting, legacy OPEN upgrade and BOOKED preservation, legacy FINAL carry at EUR 355.85, live display lots and invoice quantities, absent legacy opening-factor refusal, movement-factor snapshots, integer overflow refusal and correction comparisons without artificial unit changes. Existing ordinary PIECE, FIFO, count, HTTP, closing-flow and document export suites remain applicable.

Read-only production inspection on 20 October 2026 found zero opening-layer records and zero closing records; the legacy opening safeguard therefore does not block an existing production closing dossier at that inspection time.
