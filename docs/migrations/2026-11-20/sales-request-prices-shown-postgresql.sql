-- Prijzen gezien (Emre, 2026-11-20: "bij verkoop order deze notitie duidelijk maken of de klant de prijzen al had
-- gezien of niet").
-- sales_customer_request_message.prices_shown: whether the website showed the customer prices when they sent the
--   website request or order. From this release the intake stores it: a visitor without login sees prices only while
--   the website price switch is on (PublicQuoteResource hides them otherwise); a logged-in customer always sees their
--   own price or the list (CustomerQuoteResource does not follow the switch). Null reads as not known.
--
-- What this file fills in for the requests already stored, only where it is certain:
--   true   every request or order sent through a customer login ("... via klantlogin ..." in its internal notes).
--   false  a request without login captured on or after 2026-10-05 13:44:15 UTC, and only while the switch row still
--          reads off at row_revision 1. The switch row was created on (revision 0, website-quote-settings migration of
--          2026-09-14) and went off once (revision 1) before Emre wrote at that moment that the quote page no longer
--          showed prices; it has not moved since. Once anyone moves the switch again, this statement changes nothing.
--   null   a request without login from before that moment: the switch may still have been on.
--
-- Additive and rerunnable: one nullable column, named as its entity names it (Hibernate's schema update adds the same);
-- the updates only fill empty values. An older backend instance never reads or writes the column.
-- Runs after purchase-order-departure-by-hand-postgresql.sql.
begin;
set local lock_timeout = '15s';

alter table sales_customer_request_message add column if not exists prices_shown boolean;

update sales_customer_request_message m
   set prices_shown = true
  from sales_order o
 where o.id = m.sales_order_id
   and m.prices_shown is null
   and o.internalnotes like '[WEBSITE\_AANVRAAG]%'
   and (o.internalnotes like '%Aangevraagd via klantlogin %' or o.internalnotes like '%Besteld via klantlogin %');

update sales_customer_request_message m
   set prices_shown = false
  from sales_order o, website_quote_settings s
 where o.id = m.sales_order_id
   and m.prices_shown is null
   and s.id = 1 and s.row_revision = 1 and s.prices_visible = false
   and o.internalnotes like '[WEBSITE\_AANVRAAG]%'
   and o.internalnotes not like '%via klantlogin %'
   and m.captured_at >= timestamptz '2026-10-05 13:44:15+00';

commit;
