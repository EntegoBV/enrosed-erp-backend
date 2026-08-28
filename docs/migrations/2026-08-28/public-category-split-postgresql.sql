-- Split the customer catalogue into five stable merchandising categories.
-- Idempotent and intentionally preserves every family/channel publication status.
BEGIN;

DO $$
BEGIN
  IF (SELECT count(*) FROM category WHERE id IN (9, 10, 11, 12, 13)) <> 5 THEN
    RAISE EXCEPTION 'Expected the five canonical ENROSED category rows';
  END IF;
END $$;

UPDATE category SET code = '_category_tmp_' || id WHERE id IN (10, 11, 12, 13);
UPDATE product_collection SET collectionkey = '_collection_tmp_' || id WHERE id IN (4, 5, 6, 7);

UPDATE category SET code = 'display-roses', name = 'Display', position = 1 WHERE id = 9;
UPDATE category SET code = 'domes', name = 'Domes', position = 2, featuredproductid = 62 WHERE id = 12;
UPDATE category SET code = 'boxes', name = 'Boxes', position = 3, featuredproductid = 70 WHERE id = 10;
UPDATE category SET code = 'foam-roses', name = 'Foam', position = 4, featuredproductid = NULL WHERE id = 13;
UPDATE category SET code = 'soap-roses', name = 'Soap', position = 5 WHERE id = 11;

UPDATE product_collection SET collectionkey = 'display-roses', name = 'Display', position = 1 WHERE id = 3;
UPDATE product_collection SET collectionkey = 'domes', name = 'Domes', position = 2, featuredproductid = 62 WHERE id = 6;
UPDATE product_collection SET collectionkey = 'boxes', name = 'Boxes', position = 3, featuredproductid = 70 WHERE id = 4;
UPDATE product_collection SET collectionkey = 'foam-roses', name = 'Foam', position = 4, featuredproductid = NULL WHERE id = 7;
UPDATE product_collection SET collectionkey = 'soap-roses', name = 'Soap', position = 5 WHERE id = 5;

WITH assignments(familykey, categoryid, categorykey, position) AS (VALUES
  ('rose-diamonds-within-display', 9::bigint, 'display-roses', 0),
  ('preserved-single-rose-in-display', 9, 'display-roses', 1),
  ('preserved-bowl-rose', 9, 'display-roses', 2),
  ('bowl-rose-xl', 9, 'display-roses', 3),
  ('long-stem-rose-box-display', 9, 'display-roses', 4),
  ('cobalt-blue-roos-in-glazen-stolp', 12, 'domes', 0),
  ('rose-in-dome-elite', 12, 'domes', 1),
  ('rose-in-dome-xl', 12, 'domes', 2),
  ('rose-in-dome-m', 12, 'domes', 3),
  ('odoo-dome-15x30-single-review', 12, 'domes', 4),
  ('single-rose-in-acryl-glass-box', 10, 'boxes', 0),
  ('one-rose-in-box', 10, 'boxes', 1),
  ('hearth-glass-flowerbox', 10, 'boxes', 2),
  ('roses-in-box-16pcs', 10, 'boxes', 3),
  ('roses-in-box-9pcs', 10, 'boxes', 4),
  ('acrylic-flowerbox', 10, 'boxes', 5),
  ('glass-flowerbox', 10, 'boxes', 6),
  ('diamond-rose', 10, 'boxes', 7),
  ('odoo-heart-flowerbox-28-review', 10, 'boxes', 8),
  ('odoo-preserved-rose-windowbox', 10, 'boxes', 9),
  ('odoo-half-heart-foam-25', 13, 'foam-roses', 0),
  ('odoo-half-heart-foam-40', 13, 'foam-roses', 1),
  ('model-108-109', 13, 'foam-roses', 2),
  ('model-111-112', 13, 'foam-roses', 3),
  ('soaproos-in-vensterdoos', 11, 'soap-roses', 0),
  ('soap-rose-box-led', 11, 'soap-roses', 1),
  ('soap-roos-in-box', 11, 'soap-roses', 2)
)
UPDATE product_family family
SET categoryid = assignment.categoryid,
    categorykey = assignment.categorykey,
    categoryname = category.name,
    categoryposition = category.position,
    collectionkey = assignment.categorykey,
    productposition = assignment.position,
    updatedat = now()
FROM assignments assignment
JOIN category ON category.id = assignment.categoryid
WHERE family.familykey = assignment.familykey;

WITH copy(category_id, language, name, eyebrow, description, mobile_name, navigation_name, footer_name) AS (VALUES
  (9::bigint,'NL','Displays','Klaar voor de toonbank','Complete verkoopdisplays met individueel verpakte gepreserveerde rozen voor directe plaatsing op de winkelvloer.','Displays','Displays','Displays'),
  (9,'EN','Displays','Counter ready','Complete retail displays with individually packed preserved roses, ready for immediate shop-floor placement.','Displays','Displays','Displays'),
  (9,'FR','Présentoirs','Prêts pour le comptoir','Présentoirs complets de roses stabilisées emballées individuellement, prêts à être placés en magasin.','Présentoirs','Présentoirs','Présentoirs'),
  (9,'DE','Displays','Thekenfertig','Komplette Verkaufsdisplays mit einzeln verpackten konservierten Rosen für die direkte Platzierung im Geschäft.','Displays','Displays','Displays'),
  (9,'ES','Expositores','Listos para el mostrador','Expositores completos con rosas preservadas envasadas individualmente, listos para la tienda.','Expositores','Expositores','Expositores'),
  (9,'PL','Ekspozytory','Gotowe na ladę','Kompletne ekspozytory z osobno pakowanymi różami stabilizowanymi, gotowe do ustawienia w sklepie.','Ekspozytory','Ekspozytory','Ekspozytory'),
  (9,'PT','Expositores','Prontos para o balcão','Expositores completos com rosas preservadas embaladas individualmente, prontos para a loja.','Expositores','Expositores','Expositores'),
  (9,'TR','Teşhirler','Tezgâha hazır','Mağazada doğrudan kullanıma hazır, ayrı ayrı paketlenmiş korunmuş güllerden oluşan eksiksiz teşhirler.','Teşhirler','Teşhirler','Teşhirler'),
  (12,'NL','Stolpen','Gepreserveerd onder glas','Gepreserveerde rozen onder glas, van compacte enkele rozen tot grotere verlichte stolpen.','Stolpen','Stolpen','Stolpen'),
  (12,'EN','Domes','Preserved under glass','Preserved roses under glass, from compact single roses to larger illuminated domes.','Domes','Domes','Domes'),
  (12,'FR','Cloches','Stabilisées sous verre','Roses stabilisées sous verre, des roses simples compactes aux grandes cloches lumineuses.','Cloches','Cloches','Cloches'),
  (12,'DE','Glasglocken','Konserviert unter Glas','Konservierte Rosen unter Glas, von kompakten Einzelrosen bis zu größeren beleuchteten Glocken.','Glasglocken','Glasglocken','Glasglocken'),
  (12,'ES','Cúpulas','Preservadas bajo vidrio','Rosas preservadas bajo vidrio, desde rosas individuales compactas hasta cúpulas iluminadas.','Cúpulas','Cúpulas','Cúpulas'),
  (12,'PL','Klosze','Stabilizowane pod szkłem','Róże stabilizowane pod szkłem, od kompaktowych pojedynczych róż po większe podświetlane klosze.','Klosze','Klosze','Klosze'),
  (12,'PT','Cúpulas','Preservadas sob vidro','Rosas preservadas sob vidro, desde rosas individuais compactas a cúpulas iluminadas.','Cúpulas','Cúpulas','Cúpulas'),
  (12,'TR','Fanuslar','Cam altında korunmuş','Kompakt tek güllerden daha büyük aydınlatmalı fanuslara kadar cam altında korunmuş güller.','Fanuslar','Fanuslar','Fanuslar'),
  (10,'NL','Boxen','Cadeauklare rozenboxen','Gepreserveerde rozen in acryl-, spiegel- en flowerboxformaten, klaar voor cadeauverkoop.','Boxen','Boxen','Boxen'),
  (10,'EN','Boxes','Gift-ready rose boxes','Preserved roses in acrylic, mirror and flower-box formats, ready for gift retail.','Boxes','Boxes','Boxes'),
  (10,'FR','Boîtes','Boîtes de roses prêtes à offrir','Roses stabilisées en boîtes acryliques, miroir et flowerbox, prêtes pour la vente cadeau.','Boîtes','Boîtes','Boîtes'),
  (10,'DE','Boxen','Geschenkfertige Rosenboxen','Konservierte Rosen in Acryl-, Spiegel- und Flowerbox-Formaten für den Geschenkverkauf.','Boxen','Boxen','Boxen'),
  (10,'ES','Cajas','Cajas de rosas listas para regalar','Rosas preservadas en formatos acrílicos, de espejo y flowerbox, listas para regalo.','Cajas','Cajas','Cajas'),
  (10,'PL','Pudełka','Róże w pudełkach gotowe na prezent','Róże stabilizowane w pudełkach akrylowych, lustrzanych i flowerbox, gotowe na prezent.','Pudełka','Pudełka','Pudełka'),
  (10,'PT','Caixas','Caixas de rosas prontas a oferecer','Rosas preservadas em formatos acrílicos, espelhados e flowerbox, prontas para oferecer.','Caixas','Caixas','Caixas'),
  (10,'TR','Kutular','Hediyeye hazır gül kutuları','Hediyelik satışa hazır akrilik, aynalı ve flowerbox formatlarında korunmuş güller.','Kutular','Kutular','Kutular'),
  (13,'NL','Foam','Decoratieve foamrozen','Rozenharten en rozenberen van foam voor cadeauverkoop, seizoenspresentaties en evenementen.','Foam','Foam','Foam'),
  (13,'EN','Foam','Decorative foam roses','Foam rose hearts and bears for gift retail, seasonal displays and events.','Foam','Foam','Foam'),
  (13,'FR','Mousse','Roses décoratives en mousse','Cœurs et ours en roses de mousse pour cadeaux, présentations saisonnières et événements.','Mousse','Mousse','Mousse'),
  (13,'DE','Schaumrosen','Dekorative Schaumrosen','Herzen und Bären aus Schaumrosen für Geschenke, Saisonpräsentationen und Events.','Schaumrosen','Schaumrosen','Schaumrosen'),
  (13,'ES','Espuma','Rosas decorativas de espuma','Corazones y osos de rosas de espuma para regalos, temporadas y eventos.','Espuma','Espuma','Espuma'),
  (13,'PL','Pianka','Dekoracyjne róże piankowe','Serca i misie z róż piankowych do upominków, ekspozycji sezonowych i wydarzeń.','Pianka','Pianka','Pianka'),
  (13,'PT','Espuma','Rosas decorativas de espuma','Corações e ursos de rosas de espuma para presentes, épocas sazonais e eventos.','Espuma','Espuma','Espuma'),
  (13,'TR','Köpük','Dekoratif köpük güller','Hediyelik satış, sezonluk sunumlar ve etkinlikler için köpük gül kalpleri ve ayıcıkları.','Köpük','Köpük','Köpük'),
  (11,'NL','Soap','Cadeauklare zeeprozen','Geurende zeeprozen in vensterdozen, transparante boxen en verlichte presentaties.','Soap','Soap','Soap'),
  (11,'EN','Soap','Gift-ready soap roses','Scented soap roses in window boxes, transparent boxes and illuminated presentations.','Soap','Soap','Soap'),
  (11,'FR','Savon','Roses en savon prêtes à offrir','Roses en savon parfumées en boîtes à fenêtre, boîtes transparentes et présentations lumineuses.','Savon','Savon','Savon'),
  (11,'DE','Seife','Geschenkfertige Seifenrosen','Duftende Seifenrosen in Fensterboxen, transparenten Boxen und beleuchteten Präsentationen.','Seife','Seife','Seife'),
  (11,'ES','Jabón','Rosas de jabón listas para regalar','Rosas de jabón perfumadas en cajas con ventana, cajas transparentes y presentaciones iluminadas.','Jabón','Jabón','Jabón'),
  (11,'PL','Mydło','Róże mydlane gotowe na prezent','Pachnące róże mydlane w pudełkach z okienkiem, przezroczystych pudełkach i podświetlanych prezentacjach.','Mydło','Mydło','Mydło'),
  (11,'PT','Sabão','Rosas de sabão prontas a oferecer','Rosas de sabão perfumadas em caixas com janela, caixas transparentes e apresentações iluminadas.','Sabão','Sabão','Sabão'),
  (11,'TR','Sabun','Hediyeye hazır sabun gülleri','Pencereli kutularda, şeffaf kutularda ve aydınlatmalı sunumlarda kokulu sabun gülleri.','Sabun','Sabun','Sabun')
)
INSERT INTO category_text(category_id, language, name, eyebrow, description,
                          mobilename, navigationname, footername)
SELECT category_id, language, name, eyebrow, description,
       mobile_name, navigation_name, footer_name
FROM copy
ON CONFLICT (category_id, language) DO UPDATE SET
  name = EXCLUDED.name,
  eyebrow = EXCLUDED.eyebrow,
  description = EXCLUDED.description,
  mobilename = EXCLUDED.mobilename,
  navigationname = EXCLUDED.navigationname,
  footername = EXCLUDED.footername;

DO $$
BEGIN
  IF (SELECT count(*) FROM category_text WHERE category_id IN (9,10,11,12,13)) <> 40 THEN
    RAISE EXCEPTION 'Category split must contain 5 categories x 8 locales';
  END IF;
  IF EXISTS (
    SELECT 1 FROM product_family
    WHERE familykey <> 'model-113-114'
      AND categoryid IS NULL
  ) THEN
    RAISE EXCEPTION 'A non-test family remains uncategorised';
  END IF;
END $$;

COMMIT;
