-- The stock radar is gone (Emre, 2026-11-13, and again 2026-11-18 after its return
-- without a browser). Its first table, the last seen availability per watched shop,
-- goes with it; no business data; rerunnable. The second radar's stock_radar_watch
-- and stock_radar_recipient stay in the database untouched (nothing deleted).
drop table if exists stock_radar_store;
